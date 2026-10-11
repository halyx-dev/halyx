#!/usr/bin/env bash
# Runs the real-GCP tests, then deletes everything the run created.
#
#   scripts/gcp-real-cloud.sh                                   # project from gcloud config
#   GCP_PROJECT=my-project GCP_REGION=australia-southeast1 scripts/gcp-real-cloud.sh
#   IT_TEST='RealGcpIT$Firestore' scripts/gcp-real-cloud.sh     # one suite only
#   TIER=2 scripts/gcp-real-cloud.sh                            # Cloud SQL PostgreSQL and MySQL with IAM auth
#
# The tests and Terraform act as the test service account (GCP_IMPERSONATE, default cirrus-test@<project>),
# through Application Default Credentials that impersonate it:
#   gcloud auth application-default login --impersonate-service-account=cirrus-test@<project>.iam.gserviceaccount.com
#
# 1. Terraform creates a Cloud Storage bucket and a Firestore database of the run's own, with the indexes the
#    document store kit queries (labelled cirrus-test=<run id>).
# 2. RealGcpIT runs the blob, document store, messaging and parameter kits; it creates Pub/Sub topics and
#    subscriptions named after the run, and secrets and parameters under tck-<random> names, and deletes them.
# 3. Always, even if tests fail or the script is interrupted: sweep anything of this run the tests left behind,
#    terraform destroy, and list what remains (expected: nothing).
#
# Tier 2: Terraform creates Cloud SQL for PostgreSQL and MySQL (shared-core, IAM auth on, no backups) whose
# public IP accepts only this machine, with the test service account as an IAM database user and passwords
# generated for the run (passed through the environment only). RealGcpSqlIT logs in directly over TLS and
# through the Cloud SQL Java connector. Creating the instances takes 5-15 minutes.
set -uo pipefail

PROJECT="${GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${GCP_REGION:-australia-southeast1}"
IMPERSONATE="${GCP_IMPERSONATE:-cirrus-test@$PROJECT.iam.gserviceaccount.com}"
[[ -n "$PROJECT" ]] || { echo "Set GCP_PROJECT or a gcloud default project"; exit 1; }
export GOOGLE_CLOUD_PROJECT="$PROJECT"
gcloud auth application-default print-access-token >/dev/null 2>&1 || {
  echo "Application Default Credentials can't get a token; run:"
  echo "  gcloud auth application-default login --impersonate-service-account=$IMPERSONATE"; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TIER="${TIER:-1}"
TF_DIR="$ROOT/infra/gcp/tier$TIER"
RUN="r$(date +%y%m%d%H%M)$(openssl rand -hex 2)"
START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
TF_VARS=(-var "project=$PROJECT" -var "region=$REGION" -var "run_id=$RUN")
GCLOUD=(gcloud --project "$PROJECT" --quiet)
if [[ "$TIER" == 2 ]]; then
  CLIENT_IP=$(dig -4 +short myip.opendns.com A @208.67.222.222 | tail -1)
  [[ "$CLIENT_IP" =~ ^[0-9.]+$ ]] || { echo "Could not find this machine's public IPv4 address"; exit 1; }
  TF_VARS+=(-var "client_cidr=$CLIENT_IP/32" -var "iam_service_account=$IMPERSONATE")
  export TF_VAR_admin_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
  export TF_VAR_password_only_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
  export CIRRUS_CLOUDSQL_ADMIN_PASSWORD="$TF_VAR_admin_password"
fi

log() { printf '\n== %s\n' "$*"; }

# Parameter Manager serves regional locations from regional endpoints; gcloud sends them to the global one
# (and gets a misleading PERMISSION_DENIED) unless told otherwise.
pm() {
  local location=$1; shift
  if [[ "$location" == global ]]; then
    "${GCLOUD[@]}" parametermanager "$@" --location="$location"
  else
    CLOUDSDK_API_ENDPOINT_OVERRIDES_PARAMETERMANAGER="https://parametermanager.$location.rep.googleapis.com/" \
        "${GCLOUD[@]}" parametermanager "$@" --location="$location"
  fi
}

# Runs a listing for the final check: its output, or a CHECK FAILED marker if the listing itself failed,
# so an error is never mistaken for "nothing left".
check() {
  local what=$1; shift
  local out
  if out=$("$@" 2>/dev/null); then
    printf '%s' "$out"
  else
    printf ' CHECK FAILED (%s)' "$what"
  fi
}

sweep() {
  log "Sweeping resources of run $RUN created by the tests"
  for sub in $("${GCLOUD[@]}" pubsub subscriptions list --filter="name~/cirrus-$RUN-" --format='value(name)'); do
    echo "  deleting subscription $sub"; "${GCLOUD[@]}" pubsub subscriptions delete "$sub" >/dev/null
  done
  for topic in $("${GCLOUD[@]}" pubsub topics list --filter="name~/cirrus-$RUN-" --format='value(name)'); do
    echo "  deleting topic $topic"; "${GCLOUD[@]}" pubsub topics delete "$topic" >/dev/null
  done
  # Secrets and parameters the parameter kit created during this run (ids start with tck-).
  for secret in $("${GCLOUD[@]}" secrets list --filter="name~/secrets/tck- AND createTime>=$START" --format='value(name)'); do
    echo "  deleting secret $secret"; "${GCLOUD[@]}" secrets delete "$secret" >/dev/null
  done
  for location in global "$REGION"; do
    for parameter in $(pm "$location" parameters list \
        --filter="name~/parameters/tck- AND createTime>=$START" --format='value(name)'); do
      for version in $(pm "$location" parameters versions list --parameter="$parameter" --format='value(name)'); do
        pm "$location" parameters versions delete "$version" --parameter="$parameter" >/dev/null
      done
      echo "  deleting parameter $parameter"
      pm "$location" parameters delete "$parameter" >/dev/null
    done
  done
}

teardown() {
  local status=$?
  trap - EXIT INT TERM
  sweep
  log "terraform destroy"
  if (cd "$TF_DIR" && terraform destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null); then
    echo "  destroyed"
    rm -f "$TF_DIR"/terraform.tfstate "$TF_DIR"/terraform.tfstate.backup # tier 2 state held the run's passwords
  else
    echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR"
  fi
  log "Checking nothing of run $RUN remains"
  local left=""
  # A missing bucket makes describe fail, so check it by listing instead.
  left+=$(check buckets "${GCLOUD[@]}" storage buckets list --filter="name=cirrus-$RUN-blobs" --format='value(name)')
  left+=$(check firestore "${GCLOUD[@]}" firestore databases list --filter="name~/cirrus-$RUN\$" --format='value(name)')
  left+=$(check topics "${GCLOUD[@]}" pubsub topics list --filter="name~/cirrus-$RUN-" --format='value(name)')
  left+=$(check subscriptions "${GCLOUD[@]}" pubsub subscriptions list --filter="name~/cirrus-$RUN-" --format='value(name)')
  left+=$(check secrets "${GCLOUD[@]}" secrets list --filter="name~/secrets/tck- AND createTime>=$START" --format='value(name)')
  left+=$(check "Cloud SQL" "${GCLOUD[@]}" sql instances list --filter="name~^cirrus-$RUN-" --format='value(name)')
  for location in global "$REGION"; do
    left+=$(check "parameters in $location" pm "$location" parameters list \
        --filter="name~/parameters/tck- AND createTime>=$START" --format='value(name)')
  done
  if [[ -z "${left// /}" ]]; then
    echo "  nothing left"
  else
    echo "  STILL PRESENT:"
    echo "$left" | tr '\t' '\n' | sed 's/^/    /'
  fi
  exit $status
}
trap teardown EXIT INT TERM

log "Project $PROJECT, region $REGION, acting as $IMPERSONATE, run $RUN"
log "terraform apply"
terraform -chdir="$TF_DIR" init -input=false >/dev/null || exit 1
terraform -chdir="$TF_DIR" apply -auto-approve -input=false "${TF_VARS[@]}" || exit 1

cd "$ROOT"
if [[ "$TIER" == 2 ]]; then
  log "Running the Cloud SQL tests against real GCP"
  mvn -o verify -pl cirrus-gcp -Dit.test="${IT_TEST:-RealGcpSqlIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dcirrus.real.gcp.sql=true -Dcirrus.real.gcp.run="$RUN" -Dcirrus.real.gcp.project="$PROJECT" \
      -Dcirrus.real.gcp.service-account="$IMPERSONATE" \
      -Dcirrus.real.gcp.sql.admin-user="$(terraform -chdir="$TF_DIR" output -raw admin_user)" \
      -Dcirrus.real.gcp.sql.postgres-host="$(terraform -chdir="$TF_DIR" output -raw postgres_host)" \
      -Dcirrus.real.gcp.sql.mysql-host="$(terraform -chdir="$TF_DIR" output -raw mysql_host)" \
      -Dcirrus.real.gcp.sql.postgres-instance="$(terraform -chdir="$TF_DIR" output -raw postgres_instance)" \
      -Dcirrus.real.gcp.sql.mysql-instance="$(terraform -chdir="$TF_DIR" output -raw mysql_instance)"
  exit $?
fi
log "Running the conformance kits against real GCP"
mvn -o verify -pl cirrus-gcp -Dit.test="${IT_TEST:-RealGcpIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dcirrus.real.gcp=true -Dcirrus.real.gcp.run="$RUN" -Dcirrus.real.gcp.project="$PROJECT" \
    -Dcirrus.real.gcp.region="$REGION" \
    -Dcirrus.real.gcp.bucket="$(terraform -chdir="$TF_DIR" output -raw bucket)" \
    -Dcirrus.real.gcp.firestore-database="$(terraform -chdir="$TF_DIR" output -raw firestore_database)"
