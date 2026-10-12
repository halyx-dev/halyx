#!/usr/bin/env bash
#
# Copyright 2026 Kamran Zafar
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Runs the real-GCP tests, then deletes everything the run created.
#
#   scripts/gcp-real-cloud.sh                                   # project from gcloud config
#   GCP_PROJECT=my-project GCP_REGION=australia-southeast1 scripts/gcp-real-cloud.sh
#   IT_TEST='RealGcpIT$Firestore' scripts/gcp-real-cloud.sh     # one suite only
#   TIER=2 scripts/gcp-real-cloud.sh                            # Cloud SQL PostgreSQL and MySQL with IAM auth
#   TIER=3 scripts/gcp-real-cloud.sh                            # Memorystore for Valkey with IAM auth
#
# The tests and Terraform act as the test service account (GCP_IMPERSONATE, default halyx-test@<project>),
# through Application Default Credentials that impersonate it:
#   gcloud auth application-default login --impersonate-service-account=halyx-test@<project>.iam.gserviceaccount.com
#
# 1. Terraform creates a Cloud Storage bucket and a Firestore database of the run's own, with the indexes the
#    document store kit queries (labelled halyx-test=<run id>).
# 2. RealGcpIT runs the blob, document store, messaging and parameter kits; it creates Pub/Sub topics and
#    subscriptions named after the run, and secrets and parameters under tck-<random> names, and deletes them.
# 3. Always, even if tests fail or the script is interrupted: sweep anything of this run the tests left behind,
#    terraform destroy, and list what remains (expected: nothing).
#
# Tier 2: Terraform creates Cloud SQL for PostgreSQL and MySQL (shared-core, IAM auth on, no backups) whose
# public IP accepts only this machine, with the test service account as an IAM database user and passwords
# generated for the run (passed through the environment only). RealGcpSqlIT logs in directly over TLS and
# through the Cloud SQL Java connector. Creating the instances takes 5-15 minutes.
#
# Tier 3: Terraform creates a VPC of the run's own with a Memorystore for Valkey instance (cluster mode
# disabled, IAM auth, TLS with a private CA) and an e2-micro bastion that accepts SSH only from this machine,
# with a key generated for the run. The script fetches the instance's CA, forwards a local port through the
# bastion, and RealGcpCacheIT checks the certificate chain against that CA (the certificate can't name the
# tunnel's 127.0.0.1).
set -uo pipefail

PROJECT="${GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${GCP_REGION:-australia-southeast1}"
IMPERSONATE="${GCP_IMPERSONATE:-halyx-test@$PROJECT.iam.gserviceaccount.com}"
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
if [[ "$TIER" == 2 || "$TIER" == 3 ]]; then
  CLIENT_IP=$(dig -4 +short myip.opendns.com A @208.67.222.222 | tail -1)
  [[ "$CLIENT_IP" =~ ^[0-9.]+$ ]] || { echo "Could not find this machine's public IPv4 address"; exit 1; }
  TF_VARS+=(-var "client_cidr=$CLIENT_IP/32")
fi
if [[ "$TIER" == 3 ]]; then
  WORK="$(mktemp -d -t "halyx-$RUN")"   # run key, known_hosts, SSH control socket, the instance's CA
  ssh-keygen -q -t ed25519 -N "" -C "halyx-$RUN" -f "$WORK/key"
  TF_VARS+=(-var "ssh_public_key=$(cat "$WORK/key.pub")")
  LOCAL_PORT=16380
  SSH_OPTS=(-i "$WORK/key" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new
            -o UserKnownHostsFile="$WORK/known_hosts" -o ConnectTimeout=10 -o BatchMode=yes)
fi
if [[ "$TIER" == 2 ]]; then
  TF_VARS+=(-var "iam_service_account=$IMPERSONATE")
  export TF_VAR_admin_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
  export TF_VAR_password_only_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
  export HALYX_CLOUDSQL_ADMIN_PASSWORD="$TF_VAR_admin_password"
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
  for sub in $("${GCLOUD[@]}" pubsub subscriptions list --filter="name~/halyx-$RUN-" --format='value(name)'); do
    echo "  deleting subscription $sub"; "${GCLOUD[@]}" pubsub subscriptions delete "$sub" >/dev/null
  done
  for topic in $("${GCLOUD[@]}" pubsub topics list --filter="name~/halyx-$RUN-" --format='value(name)'); do
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
  if [[ "$TIER" == 3 && -n "${WORK:-}" ]]; then
    [[ -S "$WORK/ssh" ]] && ssh -S "$WORK/ssh" -O exit bastion 2>/dev/null && echo "  tunnel closed"
  fi
  sweep
  log "terraform destroy"
  if (cd "$TF_DIR" && terraform destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null); then
    echo "  destroyed"
    rm -f "$TF_DIR"/terraform.tfstate "$TF_DIR"/terraform.tfstate.backup # tier 2 state held the run's passwords
    [[ -n "${WORK:-}" ]] && rm -rf "$WORK"                                  # tier 3's SSH key and CA
  else
    echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR"
  fi
  log "Checking nothing of run $RUN remains"
  local left=""
  # A missing bucket makes describe fail, so check it by listing instead.
  left+=$(check buckets "${GCLOUD[@]}" storage buckets list --filter="name=halyx-$RUN-blobs" --format='value(name)')
  left+=$(check firestore "${GCLOUD[@]}" firestore databases list --filter="name~/halyx-$RUN\$" --format='value(name)')
  left+=$(check topics "${GCLOUD[@]}" pubsub topics list --filter="name~/halyx-$RUN-" --format='value(name)')
  left+=$(check subscriptions "${GCLOUD[@]}" pubsub subscriptions list --filter="name~/halyx-$RUN-" --format='value(name)')
  left+=$(check secrets "${GCLOUD[@]}" secrets list --filter="name~/secrets/tck- AND createTime>=$START" --format='value(name)')
  if [[ "$TIER" == 3 ]]; then
    left+=$(check Memorystore "${GCLOUD[@]}" memorystore instances list --location="$REGION" \
        --filter="name~/halyx-$RUN\$" --format='value(name)')
    left+=$(check "VM instances" "${GCLOUD[@]}" compute instances list --filter="name~^halyx-$RUN-" --format='value(name)')
    left+=$(check firewalls "${GCLOUD[@]}" compute firewall-rules list --filter="name~^halyx-$RUN-" --format='value(name)')
    left+=$(check "connection policies" "${GCLOUD[@]}" network-connectivity service-connection-policies list \
        --region="$REGION" --filter="name~/halyx-$RUN\$" --format='value(name)')
    left+=$(check subnets "${GCLOUD[@]}" compute networks subnets list --filter="name=halyx-$RUN" --format='value(name)')
    left+=$(check networks "${GCLOUD[@]}" compute networks list --filter="name=halyx-$RUN" --format='value(name)')
  fi
  left+=$(check "Cloud SQL" "${GCLOUD[@]}" sql instances list --filter="name~^halyx-$RUN-" --format='value(name)')
  for location in global "$REGION"; do
    left+=$(check "parameters in $location" pm "$location" parameters list \
        --filter="name~/parameters/tck- AND createTime>=$START" --format='value(name)')
  done
  if [[ -z "${left//[[:space:]]/}" ]]; then
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
if [[ "$TIER" == 3 ]]; then
  BASTION=$(terraform -chdir="$TF_DIR" output -raw bastion_ip)
  CACHE_IP=$(terraform -chdir="$TF_DIR" output -raw cache_ip)
  CACHE_PORT=$(terraform -chdir="$TF_DIR" output -raw cache_port)
  log "Fetching the instance's server CA"
  "${GCLOUD[@]}" memorystore instances get-certificate-authority "$(terraform -chdir="$TF_DIR" output -raw cache_name)" \
      --location="$REGION" --format=json \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print("\n".join(c for ca in d["managedServerCa"]["caCerts"] for c in ca["certificates"]))' \
      > "$WORK/server-ca.pem" && grep -c "BEGIN CERTIFICATE" "$WORK/server-ca.pem" | sed 's/^/  certificates: /' \
    || { echo "  could not fetch the CA"; exit 1; }
  log "Tunnelling 127.0.0.1:$LOCAL_PORT to $CACHE_IP:$CACHE_PORT through $BASTION"
  for _ in $(seq 1 36); do   # the bastion takes a minute or so to accept SSH
    ssh "${SSH_OPTS[@]}" "halyx@$BASTION" true 2>/dev/null && break
    sleep 5
  done
  ssh "${SSH_OPTS[@]}" -M -S "$WORK/ssh" -f -N -o ExitOnForwardFailure=yes \
      -L "127.0.0.1:$LOCAL_PORT:$CACHE_IP:$CACHE_PORT" "halyx@$BASTION" || { echo "  could not open the tunnel"; exit 1; }
  log "Running the Memorystore tests against real GCP"
  mvn -o verify -pl halyx-gcp -Dit.test="${IT_TEST:-RealGcpCacheIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dhalyx.real.gcp.cache=true -Dhalyx.real.gcp.run="$RUN" -Dhalyx.real.gcp.project="$PROJECT" \
      -Dhalyx.real.gcp.cache.port="$LOCAL_PORT" -Dhalyx.real.gcp.cache.ca-certificate="$WORK/server-ca.pem"
  exit $?
fi
if [[ "$TIER" == 2 ]]; then
  log "Running the Cloud SQL tests against real GCP"
  mvn -o verify -pl halyx-gcp -Dit.test="${IT_TEST:-RealGcpSqlIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dhalyx.real.gcp.sql=true -Dhalyx.real.gcp.run="$RUN" -Dhalyx.real.gcp.project="$PROJECT" \
      -Dhalyx.real.gcp.service-account="$IMPERSONATE" \
      -Dhalyx.real.gcp.sql.admin-user="$(terraform -chdir="$TF_DIR" output -raw admin_user)" \
      -Dhalyx.real.gcp.sql.postgres-host="$(terraform -chdir="$TF_DIR" output -raw postgres_host)" \
      -Dhalyx.real.gcp.sql.mysql-host="$(terraform -chdir="$TF_DIR" output -raw mysql_host)" \
      -Dhalyx.real.gcp.sql.postgres-instance="$(terraform -chdir="$TF_DIR" output -raw postgres_instance)" \
      -Dhalyx.real.gcp.sql.mysql-instance="$(terraform -chdir="$TF_DIR" output -raw mysql_instance)"
  exit $?
fi
log "Running the conformance kits against real GCP"
mvn -o verify -pl halyx-gcp -Dit.test="${IT_TEST:-RealGcpIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhalyx.real.gcp=true -Dhalyx.real.gcp.run="$RUN" -Dhalyx.real.gcp.project="$PROJECT" \
    -Dhalyx.real.gcp.region="$REGION" \
    -Dhalyx.real.gcp.bucket="$(terraform -chdir="$TF_DIR" output -raw bucket)" \
    -Dhalyx.real.gcp.firestore-database="$(terraform -chdir="$TF_DIR" output -raw firestore_database)"
