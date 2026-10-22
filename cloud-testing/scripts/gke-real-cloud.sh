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

# Runs the portable orders service on GKE and its end-to-end suite against it, then deletes everything the run
# created.
#
#   GCP_IMPERSONATE=halyx-test@<project>.iam.gserviceaccount.com cloud-testing/scripts/gke-real-cloud.sh
#   GCP_PROJECT=my-project GCP_REGION=australia-southeast1 GKE_NODE_TYPE=e2-standard-2 cloud-testing/scripts/gke-real-cloud.sh
#
# 1. Terraform creates, in a VPC of the run's own: a zonal GKE Standard cluster with Workload Identity Federation for
#    GKE, an Artifact Registry repository, and every service the sample uses (Cloud Storage, a Firestore database,
#    Pub/Sub, an Eventarc Advanced bus routed to Pub/Sub, Managed Service for Apache Kafka, Memorystore for Valkey with
#    IAM auth and TLS, Cloud SQL for PostgreSQL with IAM login, Parameter Manager and Secret Manager), plus the run's
#    settings and signing key. The nodes and the pods run as the test service account.
# 2. The script grants the service account its schema in the ledger database (as the run's admin user, from this
#    machine), builds the Spring Boot 4 and 3.5 images, pushes them, deploys each with the Helm chart (every feature on)
#    and runs DeployedServiceIT against it through a port-forward.
# 3. Always, even if tests fail or the script is interrupted: terraform destroy, and list what remains (expected:
#    nothing).
#
# Needs, once (see docs/cloud-testing/gcp.md): the Kubernetes Engine and Artifact Registry APIs, and the test service
# account with Kubernetes Engine Admin and Artifact Registry Administrator. The gcloud login grants the service's
# Kubernetes service account Workload Identity User on the test service account. Creating the Kafka cluster takes 20
# to 25 minutes.
set -uo pipefail

PROJECT="${GCP_PROJECT:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${GCP_REGION:-australia-southeast1}"
IMPERSONATE="${GCP_IMPERSONATE:-halyx-test@$PROJECT.iam.gserviceaccount.com}"
[[ -n "$PROJECT" ]] || { echo "Set GCP_PROJECT or a gcloud default project"; exit 1; }
export GOOGLE_CLOUD_PROJECT="$PROJECT"
gcloud auth application-default print-access-token >/dev/null 2>&1 || {
  echo "Application Default Credentials can't get a token; run:"
  echo "  gcloud auth application-default login --impersonate-service-account=$IMPERSONATE"; exit 1; }
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TF_DIR="$ROOT/cloud-testing/infra/gcp/k8s"
RUN="r$(date +%y%m%d%H%M)$(openssl rand -hex 2)"
CLIENT_IP=$(dig -4 +short myip.opendns.com A @208.67.222.222 | tail -1)
[[ "$CLIENT_IP" =~ ^[0-9.]+$ ]] || { echo "Could not find this machine's public IPv4 address"; exit 1; }
TF_VARS=(-var "project=$PROJECT" -var "region=$REGION" -var "run_id=$RUN" -var "service_account=$IMPERSONATE"
         -var "client_cidr=$CLIENT_IP/32")
[[ -n "${GKE_NODE_TYPE:-}" ]] && TF_VARS+=(-var "node_type=$GKE_NODE_TYPE")
export TF_VAR_admin_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 32)"
GCLOUD=(gcloud --project "$PROJECT" --quiet)
WORK="$(mktemp -d -t "halyx-$RUN")"   # kubeconfig, values
export KUBECONFIG="$WORK/kubeconfig"
REGISTRY_HOST="$REGION-docker.pkg.dev"

log() { printf '\n== %s\n' "$*"; }
# shellcheck source=k8s-portable-orders.sh
source "$ROOT/cloud-testing/scripts/k8s-portable-orders.sh"

# A token of the test service account (through Application Default Credentials), for kubectl and docker; an hour.
token() { gcloud auth application-default print-access-token; }

check() {
  local what=$1; shift
  local out
  if out=$("$@" 2>/dev/null); then
    printf '%s' "$out"
  else
    printf ' CHECK FAILED (%s)' "$what"
  fi
}

pm() { "${GCLOUD[@]}" parametermanager "$@" --location=global; }

teardown() {
  local status=$?
  trap - EXIT INT TERM
  docker logout "https://$REGISTRY_HOST" >/dev/null 2>&1
  # The release first, so no connection holds the database open while Terraform deletes it.
  [[ -s "$KUBECONFIG" ]] && helm uninstall portable-orders --namespace "$NAMESPACE" --wait >/dev/null 2>&1
  log "terraform destroy"
  if terraform -chdir="$TF_DIR" destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null; then
    echo "  destroyed"
    rm -f "$TF_DIR"/terraform.tfstate "$TF_DIR"/terraform.tfstate.backup # held the admin password and signing key
    rm -rf "$WORK"
  else
    echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR with the same -var values (run_id=$RUN)"
    [[ $status == 0 ]] && status=1   # the tests passed, but the run left something behind
  fi
  log "Checking nothing of run $RUN remains"
  local left=""
  left+=$(check clusters "${GCLOUD[@]}" container clusters list --filter="name=halyx-$RUN" --format='value(name)')
  left+=$(check repositories "${GCLOUD[@]}" artifacts repositories list --location="$REGION" \
      --filter="name~/halyx-$RUN\$" --format='value(name)')
  left+=$(check buckets "${GCLOUD[@]}" storage buckets list --filter="name=halyx-$RUN-receipts" --format='value(name)')
  left+=$(check firestore "${GCLOUD[@]}" firestore databases list --filter="name~/halyx-$RUN\$" --format='value(name)')
  left+=$(check topics "${GCLOUD[@]}" pubsub topics list --filter="name~/halyx-$RUN-" --format='value(name)')
  left+=$(check subscriptions "${GCLOUD[@]}" pubsub subscriptions list --filter="name~/halyx-$RUN-" --format='value(name)')
  for kind in message-buses pipelines enrollments; do
    left+=$(check "Eventarc $kind" "${GCLOUD[@]}" eventarc "$kind" list --location="$REGION" \
        --filter="name~/halyx-$RUN\$" --format='value(name)')
  done
  left+=$(check secrets "${GCLOUD[@]}" secrets list --filter="name~/secrets/halyx-$RUN" --format='value(name)')
  left+=$(check parameters pm parameters list --filter="name~/parameters/halyx-$RUN" --format='value(name)')
  left+=$(check Memorystore "${GCLOUD[@]}" memorystore instances list --location="$REGION" \
      --filter="name~/halyx-$RUN\$" --format='value(name)')
  left+=$(check "Managed Kafka" "${GCLOUD[@]}" managed-kafka clusters list --location="$REGION" \
      --filter="name~/halyx-$RUN\$" --format='value(name)')
  left+=$(check "Cloud SQL" "${GCLOUD[@]}" sql instances list --filter="name~^halyx-$RUN-" --format='value(name)')
  left+=$(check "VM instances" "${GCLOUD[@]}" compute instances list --filter="name~halyx-$RUN" --format='value(name)')
  left+=$(check disks "${GCLOUD[@]}" compute disks list --filter="name~halyx-$RUN" --format='value(name)')
  left+=$(check firewalls "${GCLOUD[@]}" compute firewall-rules list --filter="network~/halyx-$RUN\$" --format='value(name)')
  left+=$(check "connection policies" "${GCLOUD[@]}" network-connectivity service-connection-policies list \
      --region="$REGION" --filter="name~/halyx-$RUN\$" --format='value(name)')
  left+=$(check subnets "${GCLOUD[@]}" compute networks subnets list --filter="name=halyx-$RUN" --format='value(name)')
  left+=$(check networks "${GCLOUD[@]}" compute networks list --filter="name=halyx-$RUN" --format='value(name)')
  if [[ -z "${left//[[:space:]]/}" ]]; then
    echo "  nothing left"
  else
    echo "  STILL PRESENT:"
    [[ $status == 0 ]] && status=1   # the tests passed, but the run left something behind
    echo "$left" | tr '\t' '\n' | sed 's/^/    /'
  fi
  exit $status
}
trap teardown EXIT INT TERM

log "Project $PROJECT, region $REGION, acting as $IMPERSONATE, run $RUN"
build_images || exit 1

log "terraform apply (the Kafka cluster takes 20 to 25 minutes)"
terraform -chdir="$TF_DIR" init -input=false >/dev/null || exit 1
terraform -chdir="$TF_DIR" apply -auto-approve -input=false "${TF_VARS[@]}" || exit 1
tf() { terraform -chdir="$TF_DIR" output -raw "$1"; }

# Lets the service's Kubernetes service account act as the test service account. The project's workload identity pool
# exists only once a cluster has used it, so this is granted here, by the gcloud login; it stays for later runs.
log "Allowing $NAMESPACE/portable-orders to act as $IMPERSONATE"
"${GCLOUD[@]}" iam service-accounts add-iam-policy-binding "$IMPERSONATE" --role=roles/iam.workloadIdentityUser \
    --member="serviceAccount:$PROJECT.svc.id.goog[$NAMESPACE/portable-orders]" --format=none || exit 1

# kubectl with the service account's token: no gke-gcloud-auth-plugin needed.
printf '%s' "$(tf cluster_ca)" | base64 -d > "$WORK/cluster-ca.pem"
kubectl config set-cluster gke --server="https://$(tf endpoint)" --certificate-authority="$WORK/cluster-ca.pem" \
    --embed-certs >/dev/null
kubectl config set-credentials gke --token="$(token)" >/dev/null
kubectl config set-context gke --cluster=gke --user=gke >/dev/null && kubectl config use-context gke >/dev/null
chmod 600 "$KUBECONFIG"
kubectl get nodes -o wide | sed 's/^/  /'

token | docker login -u oauth2accesstoken --password-stdin "https://$REGISTRY_HOST" >/dev/null || exit 1
push_images "$(tf repository)/portable-orders" || exit 1

log "Granting $(tf db_user) its schema in the ledger database"
PGPASSWORD="$TF_VAR_admin_password" docker run --rm -e PGPASSWORD postgres:17-alpine psql -v ON_ERROR_STOP=1 -q \
    "host=$(tf db_public_ip) sslmode=require user=halyx_admin dbname=orders" \
    -c "GRANT ALL ON SCHEMA public TO \"$(tf db_user)\";" || exit 1

# The chart's values for this run: every feature, the run's resource names, the identity, and the cache's CA.
terraform -chdir="$TF_DIR" output -json env | SA="$IMPERSONATE" CA="$(tf cache_ca)" DB="$(tf firestore_database)" \
    INSTANCE="$(tf db_instance)" python3 -c '
import json, os, sys
values = {
    "profiles": ["gcp", "events", "streams", "cache", "sql"],
    "env": json.load(sys.stdin),
    "serviceAccount": {"name": "portable-orders",
                       "annotations": {"iam.gke.io/gcp-service-account": os.environ["SA"]}},
    "files": {"cache-ca.pem": os.environ["CA"]},
    # What environment variables cannot say: the run'"'"'s Firestore database, the cache'"'"'s private CA, and the
    # Cloud SQL connector.
    "config": json.dumps({"halyx": {
        "providers": {"gcp": {"properties": {"[gcp.firestore.database]": os.environ["DB"]}}},
        "caches": {"idempotency": {"properties": {
            "[redis.ca-certificate]": "/etc/portable-orders/files/cache-ca.pem", "[redis.verify-peer]": "ca"}}},
        "databases": {"ledger": {"properties": {"[gcp.sql.instance]": os.environ["INSTANCE"]}}},
    }}, indent=2),
}
print(json.dumps(values, indent=2))' > "$WORK/values.yaml" || exit 1

export ORDERS_E2E_FEATURES="events,streams,cache,sql" ORDERS_E2E_MAX_LINES=3 ORDERS_E2E_CURRENCY=JPY
ORDERS_E2E_SIGNING_KEY="$(tf signing_key)"
export ORDERS_E2E_SIGNING_KEY
deploy_and_test "$(tf repository)/portable-orders" gcp
