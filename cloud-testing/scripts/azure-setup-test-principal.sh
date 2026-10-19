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

# One-time setup for cloud-testing/scripts/azure-real-cloud.sh, run by a subscription Owner with the az CLI signed in:
#
#   cloud-testing/scripts/azure-setup-test-principal.sh [subscription-id]
#
# 1. Registers the resource providers tier 1 uses.
# 2. Creates the service principal halyx-test with Contributor on the subscription (to create and delete each
#    run's resource group) and writes its credentials to ~/.azure/halyx-test.env, readable only by you. The
#    secret is never printed.
# 3. Gives it Role Based Access Control Administrator on the subscription, with a condition that lets it
#    assign and remove only the data-plane roles the tests use. It can't grant itself anything else.
#    Rerunning updates the condition when the list of roles has changed.
set -euo pipefail

SUB="${1:-$(az account show --query id -o tsv)}"
CREDS="${AZURE_CREDENTIALS_FILE:-$HOME/.azure/halyx-test.env}"

echo "== Registering resource providers on $SUB"
for ns in Microsoft.Storage Microsoft.ServiceBus Microsoft.DocumentDB Microsoft.KeyVault Microsoft.AppConfiguration \
    Microsoft.EventHub Microsoft.EventGrid; do
  az provider register --subscription "$SUB" --namespace "$ns" --only-show-errors >/dev/null && echo "  $ns"
done

umask 077
mkdir -p "$(dirname "$CREDS")"
# az prints one value per line: appId, password, tenant.
save() {
  read -r id; read -r secret; read -r tenant
  [[ -n "$id" && -n "$secret" && -n "$tenant" ]] || { echo "  unexpected az output; nothing saved" >&2; return 1; }
  printf 'AZURE_CLIENT_ID=%s\nAZURE_CLIENT_SECRET=%s\nAZURE_TENANT_ID=%s\nAZURE_SUBSCRIPTION_ID=%s\n' \
      "$id" "$secret" "$tenant" "$SUB" > "$CREDS"
  echo "  appId $id; credentials saved to $CREDS"
}
APP_ID=$(az ad sp list --display-name halyx-test --query '[0].appId' -o tsv --only-show-errors)
if [[ -z "$APP_ID" ]]; then
  echo "== Creating service principal halyx-test"
  az ad sp create-for-rbac --name halyx-test --role Contributor --scopes "/subscriptions/$SUB" --only-show-errors \
      --query '[appId, password, tenant]' -o tsv | save
else
  # Rerunning: replace its secret (the old one stops working) rather than create a second principal.
  echo "== halyx-test exists ($APP_ID): issuing a new secret"
  az ad sp credential reset --id "$APP_ID" --only-show-errors --query '[appId, password, tenant]' -o tsv | save
  az role assignment create --assignee "$APP_ID" --role Contributor --scope "/subscriptions/$SUB" \
      --only-show-errors >/dev/null
fi

# Storage Blob Data Contributor, Azure Service Bus Data Owner, Key Vault Secrets Officer, App Configuration Data Owner,
# Azure Event Hubs Data Owner (tier 4: the stream kit creates and deletes event hubs), EventGrid Data Sender,
# EventGrid Data Receiver
ROLES="ba92f5b4-2d11-453d-a403-e96b0029c9fe, 090c5cfd-751d-490a-894a-3ce6f1109419, b86a8fe4-44ce-4948-aee5-eccb2c155cd7, 5ae67dd6-50cb-40e7-96ff-dc2bfa4b606b, f526a384-b230-433a-b45c-95f59c4a2dec, d5a91429-5739-47e2-a06b-3470a27159e7, 78cbd9e7-9798-4e2e-9b5a-547d9ebb31fb"
CONDITION="((!(ActionMatches{'Microsoft.Authorization/roleAssignments/write'})) OR (@Request[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {$ROLES})) AND ((!(ActionMatches{'Microsoft.Authorization/roleAssignments/delete'})) OR (@Resource[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {$ROLES}))"

echo "== Allowing it to assign only the tests' data-plane roles"
APP_ID=$(sed -n 's/^AZURE_CLIENT_ID=//p' "$CREDS")
# Query id and condition separately: tsv puts each value of a list on its own line.
list=(az role assignment list --assignee "$APP_ID" --scope "/subscriptions/$SUB"
      --role "Role Based Access Control Administrator" -o tsv)
EXISTING_ID=$("${list[@]}" --query '[0].id')
if [[ -n "$EXISTING_ID" ]]; then
  if [[ "$("${list[@]}" --query '[0].condition')" == "$CONDITION" ]]; then
    echo "  already in place"
    exit 0
  fi
  echo "  replacing the condition with the current list of roles"
  az role assignment delete --ids "$EXISTING_ID" --only-show-errors
fi
for _ in 1 2 3 4 5 6; do   # a new service principal takes a moment to appear for role assignments
  az role assignment create --assignee "$APP_ID" --role "Role Based Access Control Administrator" \
      --scope "/subscriptions/$SUB" --condition "$CONDITION" --condition-version 2.0 --only-show-errors >/dev/null \
    && { echo "  done"; exit 0; }
  sleep 10
done
echo "  could not create the role assignment; run this script's last step again" >&2
exit 1
