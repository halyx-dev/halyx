#!/usr/bin/env bash
# Runs the tier 1 conformance kits against real GCP, then deletes everything the run created.
#
#   scripts/gcp-real-cloud.sh                                   # project from gcloud config
#   GCP_PROJECT=my-project GCP_REGION=australia-southeast1 scripts/gcp-real-cloud.sh
#   IT_TEST='RealGcpIT$Firestore' scripts/gcp-real-cloud.sh     # one suite only
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
TF_DIR="$ROOT/infra/gcp/tier1"
RUN="r$(date +%y%m%d%H%M)$(openssl rand -hex 2)"
START="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
TF_VARS=(-var "project=$PROJECT" -var "region=$REGION" -var "run_id=$RUN")
GCLOUD=(gcloud --project "$PROJECT" --quiet)

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

log "Running the conformance kits against real GCP"
cd "$ROOT"
mvn -o verify -pl cirrus-gcp -Dit.test="${IT_TEST:-RealGcpIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dcirrus.real.gcp=true -Dcirrus.real.gcp.run="$RUN" -Dcirrus.real.gcp.project="$PROJECT" \
    -Dcirrus.real.gcp.region="$REGION" \
    -Dcirrus.real.gcp.bucket="$(terraform -chdir="$TF_DIR" output -raw bucket)" \
    -Dcirrus.real.gcp.firestore-database="$(terraform -chdir="$TF_DIR" output -raw firestore_database)"
