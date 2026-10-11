#!/usr/bin/env bash
# Runs the tier 1 conformance kits against real Azure, then deletes everything the run created.
#
#   scripts/azure-real-cloud.sh
#   AZURE_LOCATION=australiaeast IT_TEST='RealAzureIT$Cosmos' scripts/azure-real-cloud.sh   # one suite only
#
# Terraform and the tests act as the test service principal, whose credentials are read from
# AZURE_CREDENTIALS_FILE (default ~/.azure/cirrus-test.env, outside the repository):
#   AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID, AZURE_SUBSCRIPTION_ID
# The az CLI (your own login) is used only to check that nothing is left.
#
# 1. Terraform creates a resource group of the run's own with a storage account, a Service Bus namespace, a
#    serverless Cosmos DB account, a Key Vault and an App Configuration store, all Entra ID only, and gives the
#    principal data-plane roles on them (tagged cirrus-test=<run id>).
# 2. RealAzureIT runs the blob, document store, messaging and parameter kits; it creates Service Bus topics,
#    subscriptions and queues named after the run.
# 3. Always, even if tests fail or the script is interrupted: terraform destroy (the resource group with
#    everything in it; the Key Vault and App Configuration store are purged, not left soft-deleted), and list
#    what remains (expected: nothing).
set -uo pipefail

CREDS="${AZURE_CREDENTIALS_FILE:-$HOME/.azure/cirrus-test.env}"
[[ -r "$CREDS" ]] || { echo "No service principal credentials at $CREDS"; exit 1; }
set -a; source "$CREDS"; set +a
for v in AZURE_CLIENT_ID AZURE_CLIENT_SECRET AZURE_TENANT_ID AZURE_SUBSCRIPTION_ID; do
  [[ -n "${!v:-}" ]] || { echo "$v is missing from $CREDS"; exit 1; }
done
export ARM_CLIENT_ID="$AZURE_CLIENT_ID" ARM_CLIENT_SECRET="$AZURE_CLIENT_SECRET" \
       ARM_TENANT_ID="$AZURE_TENANT_ID" ARM_SUBSCRIPTION_ID="$AZURE_SUBSCRIPTION_ID"

LOCATION="${AZURE_LOCATION:-australiaeast}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF_DIR="$ROOT/infra/azure/tier1"
RUN="r$(date +%y%m%d%H%M)$(openssl rand -hex 2)"
TF_VARS=(-var "location=$LOCATION" -var "run_id=$RUN")
# az takes --subscription after the command, not before it.
az_() { az "$@" --subscription "$AZURE_SUBSCRIPTION_ID" --only-show-errors; }

log() { printf '\n== %s\n' "$*"; }

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

teardown() {
  local status=$?
  trap - EXIT INT TERM
  log "terraform destroy"
  if terraform -chdir="$TF_DIR" destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null; then
    echo "  destroyed"
  else
    echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR"
  fi
  log "Checking nothing of run $RUN remains"
  local left=""
  left+=$(check "resource groups" az_ group list --query "[?name=='cirrus-$RUN'].name" -o tsv)
  left+=$(check "tagged resources" az_ resource list --tag "cirrus-test=$RUN" --query '[].id' -o tsv)
  left+=$(check "deleted key vaults" az_ keyvault list-deleted --query "[?name=='cirrus-$RUN'].name" -o tsv)
  left+=$(check "deleted app configuration stores" az_ appconfig list-deleted \
      --query "[?name=='cirrus-$RUN'].name" -o tsv)
  if [[ -z "${left// /}" ]]; then
    echo "  nothing left"
  else
    echo "  STILL PRESENT:"
    echo "$left" | tr '\t' '\n' | sed 's/^/    /'
  fi
  exit $status
}
trap teardown EXIT INT TERM

log "Subscription $AZURE_SUBSCRIPTION_ID, location $LOCATION, acting as principal $AZURE_CLIENT_ID, run $RUN"
log "terraform apply"
terraform -chdir="$TF_DIR" init -input=false >/dev/null || exit 1
terraform -chdir="$TF_DIR" apply -auto-approve -input=false "${TF_VARS[@]}" || exit 1
STORAGE=$(terraform -chdir="$TF_DIR" output -raw storage_account)
CONTAINER=$(terraform -chdir="$TF_DIR" output -raw container)

# Role assignments take a while to reach the data plane. Wait until the principal can list the container.
log "Waiting for the principal's data-plane roles to take effect"
token() {  # the secret goes in on stdin, never on a command line other processes can see
  printf '%s' "$AZURE_CLIENT_SECRET" | curl -s -m 20 "https://login.microsoftonline.com/$AZURE_TENANT_ID/oauth2/v2.0/token" \
      --data-urlencode "client_id=$AZURE_CLIENT_ID" --data-urlencode "client_secret@-" \
      --data-urlencode "grant_type=client_credentials" --data-urlencode "scope=$1" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("access_token",""))'
}
for attempt in $(seq 1 30); do
  code=$(curl -s -m 20 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(token https://storage.azure.com/.default)" \
      -H "x-ms-version: 2023-11-03" "https://$STORAGE.blob.core.windows.net/$CONTAINER?restype=container&comp=list")
  [[ "$code" == 200 ]] && { echo "  blob access after $attempt attempt(s)"; break; }
  sleep 10
done
sleep 30 # the other services' assignments were made at the same time; give them a little longer

log "Running the conformance kits against real Azure"
cd "$ROOT"
mvn -o verify -pl cirrus-azure -Dit.test="${IT_TEST:-RealAzureIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dcirrus.real.azure=true -Dcirrus.real.azure.run="$RUN" \
    -Dcirrus.real.azure.storage-account="$STORAGE" -Dcirrus.real.azure.container="$CONTAINER" \
    -Dcirrus.real.azure.servicebus-namespace="$(terraform -chdir="$TF_DIR" output -raw servicebus_namespace)" \
    -Dcirrus.real.azure.cosmos-endpoint="$(terraform -chdir="$TF_DIR" output -raw cosmos_endpoint)" \
    -Dcirrus.real.azure.key-vault-url="$(terraform -chdir="$TF_DIR" output -raw key_vault_url)" \
    -Dcirrus.real.azure.appconfig-endpoint="$(terraform -chdir="$TF_DIR" output -raw appconfig_endpoint)"
