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

# Runs the portable orders service on AKS and its end-to-end suite against it, then deletes everything the run
# created.
#
#   cloud-testing/scripts/aks-real-cloud.sh
#   AZURE_LOCATION=australiaeast AKS_NODE_SIZE=Standard_B2s_v2 cloud-testing/scripts/aks-real-cloud.sh
#
# 1. Terraform creates, in a resource group of the run's own: an AKS cluster with Microsoft Entra Workload ID, a
#    container registry, a user-assigned managed identity federated to the service's Kubernetes service account, and
#    every service the sample uses (Blob Storage, Cosmos DB, Service Bus, an Event Grid namespace, Event Hubs, Azure
#    Managed Redis, Azure Database for PostgreSQL, App Configuration and Key Vault), Entra ID only. The identity gets
#    the data-plane roles; it is the database's Entra admin. Terraform also writes the run's settings and signing key.
# 2. The script builds the Spring Boot 4 and 3.5 images, pushes them to the registry, deploys each with the Helm
#    chart (every feature on) and runs DeployedServiceIT against it through a port-forward.
# 3. Always, even if tests fail or the script is interrupted: terraform destroy, and list what remains (expected:
#    nothing).
#
# Acts as the test service principal (~/.azure/halyx-test.env, see azure-setup-test-principal.sh), which may assign
# only the data-plane roles the tests use; the registry's admin credentials stand in for AcrPull.
set -uo pipefail

CREDS="${AZURE_CREDENTIALS_FILE:-$HOME/.azure/halyx-test.env}"
[[ -r "$CREDS" ]] || { echo "No service principal credentials at $CREDS"; exit 1; }
set -a; source "$CREDS"; set +a
for v in AZURE_CLIENT_ID AZURE_CLIENT_SECRET AZURE_TENANT_ID AZURE_SUBSCRIPTION_ID; do
  [[ -n "${!v:-}" ]] || { echo "$v is missing from $CREDS"; exit 1; }
done
export ARM_CLIENT_ID="$AZURE_CLIENT_ID" ARM_CLIENT_SECRET="$AZURE_CLIENT_SECRET" \
       ARM_TENANT_ID="$AZURE_TENANT_ID" ARM_SUBSCRIPTION_ID="$AZURE_SUBSCRIPTION_ID"

LOCATION="${AZURE_LOCATION:-australiaeast}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TF_DIR="$ROOT/cloud-testing/infra/azure/k8s"
RUN="r$(date +%y%m%d%H%M)$(openssl rand -hex 2)"
TF_VARS=(-var "location=$LOCATION" -var "run_id=$RUN")
[[ -n "${AKS_NODE_SIZE:-}" ]] && TF_VARS+=(-var "node_size=$AKS_NODE_SIZE")
WORK="$(mktemp -d -t "halyx-$RUN")"   # kubeconfig, values
export KUBECONFIG="$WORK/kubeconfig"
REGISTRY=""
az_() { az "$@" --subscription "$AZURE_SUBSCRIPTION_ID" --only-show-errors; }

log() { printf '\n== %s\n' "$*"; }
# shellcheck source=k8s-portable-orders.sh
source "$ROOT/cloud-testing/scripts/k8s-portable-orders.sh"

token() {  # the secret goes in on stdin, never on a command line other processes can see
  printf '%s' "$AZURE_CLIENT_SECRET" | curl -s -m 20 "https://login.microsoftonline.com/$AZURE_TENANT_ID/oauth2/v2.0/token" \
      --data-urlencode "client_id=$AZURE_CLIENT_ID" --data-urlencode "client_secret@-" \
      --data-urlencode "grant_type=client_credentials" --data-urlencode "scope=$1" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))'
}

# Registers resource providers as the principal (Contributor may), waiting until they are ready to use.
register() {
  local arm="https://management.azure.com/subscriptions/$AZURE_SUBSCRIPTION_ID/providers"
  for ns in "$@"; do
    local bearer="Authorization: Bearer $(token https://management.azure.com/.default)"
    curl -s -m 30 -o /dev/null -X POST -H "$bearer" -H "Content-Length: 0" "$arm/$ns/register?api-version=2021-04-01"
    for _ in $(seq 1 60); do
      state=$(curl -s -m 30 -H "$bearer" "$arm/$ns?api-version=2021-04-01" \
          | python3 -c 'import json,sys; print(json.load(sys.stdin).get("registrationState",""))')
      [[ "$state" == Registered ]] && break
      sleep 10
    done
    echo "  $ns: $state"
  done
}

# Runs a listing for the final check: its output, or a CHECK FAILED marker if the listing itself failed.
check() {
  local what=$1; shift
  local out
  if out=$("$@" 2>/dev/null); then
    printf '%s' "$out"
  else
    printf ' CHECK FAILED (%s)' "$what"
  fi
}

teardown() {
  local status=$?
  trap - EXIT INT TERM
  [[ -n "$REGISTRY" ]] && docker logout "$REGISTRY" >/dev/null 2>&1
  # The release first, so no connection holds the database open while Terraform deletes it.
  [[ -s "$KUBECONFIG" ]] && helm uninstall portable-orders --namespace "$NAMESPACE" --wait >/dev/null 2>&1
  log "terraform destroy"
  # Deleting the server deletes its database, firewall rule and Entra ID administrator with it. Deleting them one by
  # one first has hung in Azure until Terraform timed out, so Terraform forgets them and deletes only the server.
  for child in azurerm_postgresql_flexible_server_database.orders azurerm_postgresql_flexible_server_firewall_rule.azure \
      azurerm_postgresql_flexible_server_active_directory_administrator.app; do
    terraform -chdir="$TF_DIR" state rm "$child" >/dev/null 2>&1   # absent after a partial apply
  done
  if terraform -chdir="$TF_DIR" destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null; then
    echo "  destroyed"
    rm -f "$TF_DIR"/terraform.tfstate "$TF_DIR"/terraform.tfstate.backup # held the registry password and signing key
    rm -rf "$WORK"
  else
    echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR with -var location=$LOCATION -var run_id=$RUN"
    [[ $status == 0 ]] && status=1   # the tests passed, but the run left something behind
  fi
  log "Checking nothing of run $RUN remains"
  local left
  for attempt in 1 2 3; do
    left=""
    # The cluster's node resource group (MC_...) goes with the cluster.
    left+=$(check "resource groups" az_ group list --query "[?contains(name, 'halyx-$RUN')].name" -o tsv)
    left+=$(check "tagged resources" az_ resource list --tag "halyx-test=$RUN" --query '[].id' -o tsv)
    left+=$(check "deleted key vaults" az_ keyvault list-deleted --query "[?name=='halyx-$RUN'].name" -o tsv)
    left+=$(check "deleted app configuration stores" az_ appconfig list-deleted \
        --query "[?name=='halyx-$RUN'].name" -o tsv)
    [[ -z "${left//[[:space:]]/}" || $attempt == 3 ]] && break
    echo "  something is still listed; looking again in 60s"
    sleep 60
  done
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

log "Subscription $AZURE_SUBSCRIPTION_ID, location $LOCATION, acting as principal $AZURE_CLIENT_ID, run $RUN"
build_images || exit 1
log "Registering resource providers"
register Microsoft.ContainerService Microsoft.ContainerRegistry Microsoft.ManagedIdentity Microsoft.Storage \
    Microsoft.DocumentDB Microsoft.ServiceBus Microsoft.EventGrid Microsoft.EventHub Microsoft.Cache \
    Microsoft.DBforPostgreSQL Microsoft.KeyVault Microsoft.AppConfiguration

log "terraform apply (the cluster, Managed Redis and PostgreSQL take 10 to 15 minutes)"
terraform -chdir="$TF_DIR" init -input=false >/dev/null || exit 1
terraform -chdir="$TF_DIR" apply -auto-approve -input=false "${TF_VARS[@]}" || exit 1
tf() { terraform -chdir="$TF_DIR" output -raw "$1"; }

terraform -chdir="$TF_DIR" output -raw kubeconfig > "$KUBECONFIG" && chmod 600 "$KUBECONFIG"
[[ -s "$KUBECONFIG" ]] || { echo "  Terraform gave no kubeconfig for the cluster"; exit 1; }
kubectl get nodes -o wide | sed 's/^/  /'
REGISTRY="$(tf registry)"
tf registry_password | docker login "$REGISTRY" -u "$(tf registry_user)" --password-stdin >/dev/null || exit 1
push_images "$REGISTRY/portable-orders" || exit 1
kubectl create namespace "$NAMESPACE" >/dev/null || exit 1
# The pull secret goes in as a manifest on stdin, so the password is never on a command line.
REGISTRY="$REGISTRY" USER_="$(tf registry_user)" PASSWORD_="$(tf registry_password)" python3 -c '
import base64, json, os
user, password = os.environ["USER_"], os.environ["PASSWORD_"]
auth = base64.b64encode(f"{user}:{password}".encode()).decode()
config = {"auths": {os.environ["REGISTRY"]: {"auth": auth}}}
print(json.dumps({"apiVersion": "v1", "kind": "Secret", "type": "kubernetes.io/dockerconfigjson",
                  "metadata": {"name": "registry"},
                  "data": {".dockerconfigjson": base64.b64encode(json.dumps(config).encode()).decode()}}))' \
  | kubectl -n "$NAMESPACE" apply -f - >/dev/null || exit 1

# The chart's values for this run: every feature, the run's resource names, and the workload identity.
terraform -chdir="$TF_DIR" output -json env | CLIENT_ID="$(tf client_id)" python3 -c '
import json, os, sys
env = json.load(sys.stdin)
values = {
    "profiles": ["azure", "events", "streams", "cache", "sql"],
    "env": env,
    "imagePullSecrets": [{"name": "registry"}],
    "serviceAccount": {"name": "portable-orders",
                       "annotations": {"azure.workload.identity/client-id": os.environ["CLIENT_ID"]}},
    "podLabels": {"azure.workload.identity/use": "true"},
}
print(json.dumps(values, indent=2))' > "$WORK/values.yaml" || exit 1   # JSON is YAML

export ORDERS_E2E_FEATURES="events,streams,cache,sql" ORDERS_E2E_MAX_LINES=4 ORDERS_E2E_CURRENCY=NZD
ORDERS_E2E_SIGNING_KEY="$(tf signing_key)"
export ORDERS_E2E_SIGNING_KEY
deploy_and_test "$REGISTRY/portable-orders" azure
