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

# Runs the real-Azure tests, then deletes everything the run created.
#
#   cloud-testing/scripts/azure-real-cloud.sh
#   AZURE_LOCATION=australiaeast IT_TEST='RealAzureIT$Cosmos' cloud-testing/scripts/azure-real-cloud.sh   # one suite only
#   TIER=2 cloud-testing/scripts/azure-real-cloud.sh     # Azure Database for PostgreSQL and Azure SQL with Entra ID login
#   TIER=3 cloud-testing/scripts/azure-real-cloud.sh     # Azure Managed Redis with Entra ID
#   TIER=4 cloud-testing/scripts/azure-real-cloud.sh     # Event Hubs (Kafka endpoint) with Entra ID, plus Spring Boot 3 and 4
#   TIER=5 cloud-testing/scripts/azure-real-cloud.sh     # cloud logins: a service principal with a secret and a federated token
#
# Terraform and the tests act as the test service principal, whose credentials are read from
# AZURE_CREDENTIALS_FILE (default ~/.azure/halyx-test.env, outside the repository):
#   AZURE_CLIENT_ID, AZURE_CLIENT_SECRET, AZURE_TENANT_ID, AZURE_SUBSCRIPTION_ID
# The az CLI (your own login) is used only to check that nothing is left.
#
# 1. Terraform creates a resource group of the run's own with a storage account, a Service Bus namespace, a
#    serverless Cosmos DB account, a Key Vault and an App Configuration store, all Entra ID only, and gives the
#    principal data-plane roles on them (tagged halyx-test=<run id>).
# 2. RealAzureIT runs the blob, document store, messaging and parameter kits; it creates Service Bus topics,
#    subscriptions and queues named after the run.
# 3. Always, even if tests fail or the script is interrupted: terraform destroy (the resource group with
#    everything in it; the Key Vault and App Configuration store are purged, not left soft-deleted), and list
#    what remains (expected: nothing).
#
# Tier 2: a PostgreSQL flexible server (Entra and password auth, a password admin generated for the run and
# passed through the environment only) and an Entra-only Azure SQL database, the principal as Entra admin of
# both, reachable only from this machine's address. RealAzureSqlIT logs in with Entra tokens.
# Tier 3: Azure Managed Redis with access keys off; the principal gets the "default" access policy.
# RealAzureCacheIT runs the cache kit with Entra tokens over TLS on the public endpoint.
# Tier 4: three Standard Event Hubs namespaces with shared access keys off; the principal is their Data Owner.
# RealAzureStreamIT runs the stream kit on its Kafka endpoint with Entra tokens, creating and deleting event
# hubs as it goes.
# Tier 5: a storage account the principal may use. RealAzureLoginIT names the principal explicitly, logging in
# with its secret and with a federated token, and checks that a wrong secret and an untrusted token are refused.
# The federated token is a Google ID token for AZURE_FEDERATED_GOOGLE_SA (a Google Cloud service account you may
# impersonate), which the application trusts through a federated identity credential set up once; without
# AZURE_FEDERATED_GOOGLE_SA that test is skipped.
# Tiers 2, 3 and 4 register their resource providers first, as the principal.
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
TIER="${TIER:-1}"
TF_DIR="$ROOT/cloud-testing/infra/azure/tier$TIER"
RUN="r$(date +%y%m%d%H%M)$(openssl rand -hex 2)"
TF_VARS=(-var "location=$LOCATION" -var "run_id=$RUN")
# az takes --subscription after the command, not before it.
az_() { az "$@" --subscription "$AZURE_SUBSCRIPTION_ID" --only-show-errors; }

log() { printf '\n== %s\n' "$*"; }

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

if [[ "$TIER" == 2 ]]; then
  CLIENT_IP=$(dig -4 +short myip.opendns.com A @208.67.222.222 | tail -1)
  [[ "$CLIENT_IP" =~ ^[0-9.]+$ ]] || { echo "Could not find this machine's public IPv4 address"; exit 1; }
  TF_VARS+=(-var "client_ip=$CLIENT_IP")
  export TF_VAR_pg_admin_password="$(openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | head -c 30)Aa1"
  export HALYX_AZURE_PG_ADMIN_PASSWORD="$TF_VAR_pg_admin_password"
fi

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
  [[ -n "${WORK:-}" ]] && rm -rf "$WORK"   # tier 5's federated token
  log "terraform destroy"
  if terraform -chdir="$TF_DIR" destroy -auto-approve -input=false "${TF_VARS[@]}" >/dev/null; then
    echo "  destroyed"
    rm -f "$TF_DIR"/terraform.tfstate "$TF_DIR"/terraform.tfstate.backup # tier 2 state held the run's password
  else
    echo "  TERRAFORM DESTROY FAILED: run it again in $TF_DIR"
    [[ $status == 0 ]] && status=1   # the tests passed, but the run left something behind
  fi
  log "Checking nothing of run $RUN remains"
  local left
  # Azure's resource index can list resources for a minute or two after their group is deleted; look again
  # before reporting anything.
  for attempt in 1 2 3; do
    left=""
    left+=$(check "resource groups" az_ group list --query "[?name=='halyx-$RUN'].name" -o tsv)
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
case "$TIER" in
  1) log "Registering resource providers"; register Microsoft.EventGrid ;;
  2) log "Registering resource providers"; register Microsoft.DBforPostgreSQL Microsoft.Sql ;;
  3) log "Registering resource providers"; register Microsoft.Cache ;;
  4) log "Registering resource providers"; register Microsoft.EventHub ;;
esac
log "terraform apply"
terraform -chdir="$TF_DIR" init -input=false >/dev/null || exit 1
terraform -chdir="$TF_DIR" apply -auto-approve -input=false "${TF_VARS[@]}" || exit 1
cd "$ROOT"

if [[ "$TIER" == 2 ]]; then
  log "Running the Azure Database tests against real Azure"
  mvn -o verify -pl halyx-azure -Dit.test="${IT_TEST:-RealAzureSqlIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dhalyx.real.azure.sql=true -Dhalyx.real.azure.run="$RUN" \
      -Dhalyx.real.azure.sql.principal-name="$(terraform -chdir="$TF_DIR" output -raw principal_name)" \
      -Dhalyx.real.azure.sql.postgres-host="$(terraform -chdir="$TF_DIR" output -raw postgres_host)" \
      -Dhalyx.real.azure.sql.sql-host="$(terraform -chdir="$TF_DIR" output -raw sql_host)"
  status=$?
  [[ -n "${IT_TEST:-}" ]] && exit $status
  # Spring Boot 3 and 4: Spring Data R2DBC over halyx.r2dbc-databases, logging in to PostgreSQL with the identity.
  mvn -o -q install -DskipTests -pl halyx-spring-boot-autoconfigure -am || exit 1
  for module in halyx-spring-boot-autoconfigure halyx-spring-boot4-tests; do
    log "Running the Spring Boot R2DBC test ($module) against real Azure"
    mvn -o verify -pl "$module" -Dit.test=RealSpringR2dbcIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
        -Dhalyx.real.spring.r2dbc=true -Dhalyx.real.spring.provider=azure \
        -Dhalyx.real.spring.r2dbc.host="$(terraform -chdir="$TF_DIR" output -raw postgres_host)" \
        -Dhalyx.real.spring.r2dbc.user="$(terraform -chdir="$TF_DIR" output -raw principal_name)" || status=1
  done
  exit $status
fi
if [[ "$TIER" == 3 ]]; then
  log "Waiting for the access policy assignment to take effect"
  sleep 60
  log "Running the Azure Managed Redis tests against real Azure"
  mvn -o verify -pl halyx-azure -Dit.test="${IT_TEST:-RealAzureCacheIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dhalyx.real.azure.cache=true -Dhalyx.real.azure.run="$RUN" \
      -Dhalyx.real.azure.cache.host="$(terraform -chdir="$TF_DIR" output -raw cache_host)" \
      -Dhalyx.real.azure.cache.port="$(terraform -chdir="$TF_DIR" output -raw cache_port)"
  status=$?
  [[ -n "${IT_TEST:-}" ]] && exit $status
  # Spring Boot 3 and 4: Cache beans and @Cacheable logging in with the cloud identity.
  mvn -o -q install -DskipTests -pl halyx-spring-boot-autoconfigure -am || exit 1
  for module in halyx-spring-boot-autoconfigure halyx-spring-boot4-tests; do
    log "Running the Spring Boot cache test ($module) against real Azure"
    mvn -o verify -pl "$module" -Dit.test=RealSpringCacheIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
        -Dhalyx.real.spring.cache=true -Dhalyx.real.spring.provider=azure \
        -Dhalyx.real.spring.cache-endpoint="rediss://$(terraform -chdir="$TF_DIR" output -raw cache_host):$(terraform -chdir="$TF_DIR" output -raw cache_port)" || status=1
  done
  exit $status
fi

if [[ "$TIER" == 4 ]]; then
  log "Waiting for the Data Owner role to take effect"
  sleep 90
  log "Running the stream kit against real Event Hubs"
  NAMESPACES="$(terraform -chdir="$TF_DIR" output -raw eventhubs_namespaces)"
  # KAFKA_CLIENTS=4.2.1 runs the same tests on another version of Kafka's clients.
  mvn -o verify -pl halyx-azure -Dit.test="${IT_TEST:-RealAzureStreamIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      ${KAFKA_CLIENTS:+-Dkafka.version="$KAFKA_CLIENTS"} \
      -Dhalyx.real.azure.streams=true -Dhalyx.real.azure.run="$RUN" -Dhalyx.real.azure.streams.namespaces="$NAMESPACES"
  status=$?
  [[ -n "${IT_TEST:-}" ]] && exit $status
  # Spring Boot 3 and 4, with the Kafka clients each manages, on the namespace the kit used least.
  mvn -o -q install -DskipTests -pl halyx-spring-boot-autoconfigure -am || exit 1
  for module in halyx-spring-boot-autoconfigure halyx-spring-boot4-tests; do
    log "Running the Spring Boot stream test ($module) against real Event Hubs"
    mvn -o verify -pl "$module" -Dit.test=RealSpringStreamIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
        -Dhalyx.real.spring.streams=true -Dhalyx.real.spring.provider=azure \
        -Dhalyx.real.spring.bootstrap="${NAMESPACES##*,}:9093" || status=1
  done
  exit $status
fi

STORAGE=$(terraform -chdir="$TF_DIR" output -raw storage_account)
CONTAINER=$(terraform -chdir="$TF_DIR" output -raw container)

# Role assignments take a while to reach the data plane. Wait until the principal can list the container.
wait_for_blob_access() {
  log "Waiting for the principal's data-plane roles to take effect"
  for attempt in $(seq 1 30); do
    code=$(curl -s -m 20 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(token https://storage.azure.com/.default)" \
        -H "x-ms-version: 2023-11-03" "https://$STORAGE.blob.core.windows.net/$CONTAINER?restype=container&comp=list")
    [[ "$code" == 200 ]] && { echo "  blob access after $attempt attempt(s)"; return; }
    sleep 10
  done
}

if [[ "$TIER" == 5 ]]; then
  wait_for_blob_access
  TOKEN_FILE=""
  if [[ -n "${AZURE_FEDERATED_GOOGLE_SA:-}" ]]; then
    log "Getting a Google ID token for $AZURE_FEDERATED_GOOGLE_SA"
    WORK="$(mktemp -d -t "halyx-$RUN")"
    TOKEN_FILE="$WORK/google-token"
    (umask 077; gcloud auth print-identity-token --impersonate-service-account="$AZURE_FEDERATED_GOOGLE_SA" \
        --audiences=api://AzureADTokenExchange --include-email 2>/dev/null > "$TOKEN_FILE") \
      && [[ -s "$TOKEN_FILE" ]] || { echo "  could not get one"; exit 1; }
  fi
  log "Running the cloud login tests against real Azure"
  mvn -o verify -pl halyx-azure -Dit.test="${IT_TEST:-RealAzureLoginIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
      -Dhalyx.real.azure.logins=true -Dhalyx.real.azure.run="$RUN" \
      -Dhalyx.real.azure.logins.storage-account="$STORAGE" -Dhalyx.real.azure.logins.container="$CONTAINER" \
      -Dhalyx.real.azure.logins.token-file="$TOKEN_FILE"
  status=$?
  [[ -n "${IT_TEST:-}" ]] && exit $status
  # Spring Boot 3 and 4: credentials.type service-principal, with the secret from the environment, and with the
  # federated token when there is one.
  PRINCIPAL='type=service-principal,tenant-id=${AZURE_TENANT_ID},client-id=${AZURE_CLIENT_ID}'
  LOGINS=("$PRINCIPAL,secret=\${AZURE_CLIENT_SECRET}")
  [[ -n "$TOKEN_FILE" ]] && LOGINS+=("$PRINCIPAL,token-file=$TOKEN_FILE")
  mvn -o -q install -DskipTests -pl halyx-spring-boot-autoconfigure -am || exit 1
  for module in halyx-spring-boot-autoconfigure halyx-spring-boot4-tests; do
    for login in "${LOGINS[@]}"; do
      log "Running the Spring Boot login test ($module, ${login##*,}) against real Azure"
      mvn -o verify -pl "$module" -Dit.test=RealSpringLoginIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
          -Dhalyx.real.spring.logins=true -Dhalyx.real.spring.provider=azure -Dhalyx.real.spring.bucket="$CONTAINER" \
          -Dhalyx.real.spring.provider-properties="azure.storage.account=$STORAGE" \
          -Dhalyx.real.spring.credentials="$login" || status=1
    done
  done
  exit $status
fi

EVENT_GRID=$(terraform -chdir="$TF_DIR" output -raw eventgrid_endpoint)
EVENT_GRID_TOPIC=$(terraform -chdir="$TF_DIR" output -raw eventgrid_topic)
EVENT_GRID_SUBSCRIPTION=$(terraform -chdir="$TF_DIR" output -raw eventgrid_subscription)
FUNCTION_ENDPOINT=$(terraform -chdir="$TF_DIR" output -raw function_endpoint)
# Read by the tests from the environment, so the key stays off the command line
export HALYX_FUNCTION_KEY="$(terraform -chdir="$TF_DIR" output -raw function_key)"

wait_for_blob_access
sleep 30 # the other services' assignments were made at the same time; give them a little longer

log "Running the conformance kits against real Azure"
mvn -o verify -pl halyx-azure -Dit.test="${IT_TEST:-RealAzureIT}" -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false \
    -Dhalyx.real.azure=true -Dhalyx.real.azure.run="$RUN" \
    -Dhalyx.real.azure.storage-account="$STORAGE" -Dhalyx.real.azure.container="$CONTAINER" \
    -Dhalyx.real.azure.servicebus-namespace="$(terraform -chdir="$TF_DIR" output -raw servicebus_namespace)" \
    -Dhalyx.real.azure.cosmos-endpoint="$(terraform -chdir="$TF_DIR" output -raw cosmos_endpoint)" \
    -Dhalyx.real.azure.key-vault-url="$(terraform -chdir="$TF_DIR" output -raw key_vault_url)" \
    -Dhalyx.real.azure.appconfig-endpoint="$(terraform -chdir="$TF_DIR" output -raw appconfig_endpoint)" \
    -Dhalyx.real.azure.eventgrid-endpoint="$EVENT_GRID" -Dhalyx.real.azure.eventgrid-topic="$EVENT_GRID_TOPIC" \
    -Dhalyx.real.azure.eventgrid-subscription="$EVENT_GRID_SUBSCRIPTION" \
    -Dhalyx.real.azure.function-endpoint="$FUNCTION_ENDPOINT"
status=$?
[[ -n "${IT_TEST:-}" ]] && exit $status
# Spring Boot 3 and 4: an auto-configured repository on the run's table with a sort key, the event bus, the
# reactive beans, @HalyxListener on the run's Service Bus topic with a claim check, spring.config.import from
# Key Vault and App Configuration, and a FunctionInvoker bean on the run's function with its function key.
mvn -o -q install -DskipTests -pl halyx-spring-boot-autoconfigure -am || exit 1
for module in halyx-spring-boot-autoconfigure halyx-spring-boot4-tests; do
  log "Running the Spring Boot repository, event and reactive tests ($module) against real Azure"
  mvn -o verify -pl "$module" -Dit.test='RealSpringRepositoryIT,RealSpringEventIT,RealSpringReactiveIT,RealSpringMessagingIT,RealSpringParametersIT,RealSpringFunctionIT' -Dtest=none \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dhalyx.real.spring.repositories=true -Dhalyx.real.spring.events=true -Dhalyx.real.spring.reactive=true \
      -Dhalyx.real.spring.messaging=true -Dhalyx.real.spring.parameters=true -Dhalyx.real.spring.functions=true \
      -Dhalyx.real.spring.function=tck \
      -Dhalyx.real.spring.function-properties="azure.functions.endpoint=$FUNCTION_ENDPOINT,azure.functions.key=\${HALYX_FUNCTION_KEY}" \
      -Dhalyx.real.spring.provider=azure -Dhalyx.real.spring.bucket="$CONTAINER" \
      -Dhalyx.real.spring.table="items-sorted" \
      -Dhalyx.real.spring.provider-properties="azure.cosmos.endpoint=$(terraform -chdir="$TF_DIR" output -raw cosmos_endpoint),azure.cosmos.database=halyx,azure.storage.account=$STORAGE,azure.servicebus.namespace=$(terraform -chdir="$TF_DIR" output -raw servicebus_namespace)" \
      -Dhalyx.real.spring.event-bus="$EVENT_GRID_TOPIC" -Dhalyx.real.spring.event-bus-properties="azure.eventgrid.endpoint=$EVENT_GRID" \
      -Dhalyx.real.spring.inbox="$EVENT_GRID_SUBSCRIPTION" -Dhalyx.real.spring.inbox-topic="$EVENT_GRID_TOPIC" \
      -Dhalyx.real.spring.inbox-properties="azure.eventgrid.endpoint=$EVENT_GRID" \
      -Dhalyx.real.spring.topic="$(terraform -chdir="$TF_DIR" output -raw spring_topic)" \
      -Dhalyx.real.spring.subscription="$(terraform -chdir="$TF_DIR" output -raw spring_subscription)" \
      -Dhalyx.real.spring.secrets-location="$(terraform -chdir="$TF_DIR" output -raw key_vault_url)" \
      -Dhalyx.real.spring.parameters-location="$(terraform -chdir="$TF_DIR" output -raw appconfig_endpoint)" || status=1
done
exit $status
