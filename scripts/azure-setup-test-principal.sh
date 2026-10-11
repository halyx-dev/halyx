#!/usr/bin/env bash
# One-time setup for scripts/azure-real-cloud.sh, run by a subscription Owner with the az CLI signed in:
#
#   scripts/azure-setup-test-principal.sh [subscription-id]
#
# 1. Registers the resource providers tier 1 uses.
# 2. Creates the service principal cirrus-test with Contributor on the subscription (to create and delete each
#    run's resource group) and writes its credentials to ~/.azure/cirrus-test.env, readable only by you. The
#    secret is never printed.
# 3. Gives it Role Based Access Control Administrator on the subscription, with a condition that lets it
#    assign and remove only the four data-plane roles the tests use. It can't grant itself anything else.
set -euo pipefail

SUB="${1:-$(az account show --query id -o tsv)}"
CREDS="${AZURE_CREDENTIALS_FILE:-$HOME/.azure/cirrus-test.env}"

echo "== Registering resource providers on $SUB"
for ns in Microsoft.Storage Microsoft.ServiceBus Microsoft.DocumentDB Microsoft.KeyVault Microsoft.AppConfiguration; do
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
APP_ID=$(az ad sp list --display-name cirrus-test --query '[0].appId' -o tsv --only-show-errors)
if [[ -z "$APP_ID" ]]; then
  echo "== Creating service principal cirrus-test"
  az ad sp create-for-rbac --name cirrus-test --role Contributor --scopes "/subscriptions/$SUB" --only-show-errors \
      --query '[appId, password, tenant]' -o tsv | save
else
  # Rerunning: replace its secret (the old one stops working) rather than create a second principal.
  echo "== cirrus-test exists ($APP_ID): issuing a new secret"
  az ad sp credential reset --id "$APP_ID" --only-show-errors --query '[appId, password, tenant]' -o tsv | save
  az role assignment create --assignee "$APP_ID" --role Contributor --scope "/subscriptions/$SUB" \
      --only-show-errors >/dev/null
fi

# Storage Blob Data Contributor, Azure Service Bus Data Owner, Key Vault Secrets Officer, App Configuration Data Owner
ROLES="ba92f5b4-2d11-453d-a403-e96b0029c9fe, 090c5cfd-751d-490a-894a-3ce6f1109419, b86a8fe4-44ce-4948-aee5-eccb2c155cd7, 5ae67dd6-50cb-40e7-96ff-dc2bfa4b606b"
CONDITION="((!(ActionMatches{'Microsoft.Authorization/roleAssignments/write'})) OR (@Request[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {$ROLES})) AND ((!(ActionMatches{'Microsoft.Authorization/roleAssignments/delete'})) OR (@Resource[Microsoft.Authorization/roleAssignments:RoleDefinitionId] ForAnyOfAnyValues:GuidEquals {$ROLES}))"

echo "== Allowing it to assign only the tests' data-plane roles"
APP_ID=$(sed -n 's/^AZURE_CLIENT_ID=//p' "$CREDS")
if [[ "$(az role assignment list --assignee "$APP_ID" --scope "/subscriptions/$SUB" \
    --role "Role Based Access Control Administrator" --query 'length(@)' -o tsv)" != 0 ]]; then
  echo "  already in place"
  exit 0
fi
for _ in 1 2 3 4 5 6; do   # a new service principal takes a moment to appear for role assignments
  az role assignment create --assignee "$APP_ID" --role "Role Based Access Control Administrator" \
      --scope "/subscriptions/$SUB" --condition "$CONDITION" --condition-version 2.0 --only-show-errors >/dev/null \
    && { echo "  done"; exit 0; }
  sleep 10
done
echo "  could not create the role assignment; run this script's last step again" >&2
exit 1
