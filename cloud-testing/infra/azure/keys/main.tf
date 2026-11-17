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

# The Key Vault keys the real-cloud key tests use, and the test principal's roles on them. Applied once, by a
# subscription Owner signed in with the az CLI, and kept, as on AWS and GCP (infra/aws/keys, infra/gcp/keys). The run
# scripts never apply or destroy it.
#
#   terraform -chdir=cloud-testing/infra/azure/keys init
#   terraform -chdir=cloud-testing/infra/azure/keys apply -var location=australiaeast \
#       -var test_principal=$(az ad sp list --display-name halyx-test --query '[0].id' -o tsv)
#
# The resource group and vault are tagged halyx-keys=long-lived, never halyx-test, so no run's teardown reaches them.
# Each run's storage account gets Key Vault Crypto Service Encryption User on tck-server-side from tier 1, for its
# encryption scope; the assignment goes with the run.

terraform {
  required_version = ">= 1.5"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.12"
    }
  }
}

variable "location" {
  type = string
}

variable "test_principal" {
  type        = string
  description = "The object ID of the test service principal (halyx-test) the real-cloud scripts sign in as"
}

provider "azurerm" {
  resource_provider_registrations = "none" # registered by azure-setup-test-principal.sh
  features {
    key_vault {
      purge_soft_delete_on_destroy    = true # if this is ever destroyed, free the vault's name
      recover_soft_deleted_key_vaults = true
    }
  }
}

data "azurerm_client_config" "current" {}

locals {
  tags = { halyx-keys = "long-lived" }
  # Vault names are global; this one is stable per subscription.
  vault = "halyx-keys-${substr(sha1(data.azurerm_client_config.current.subscription_id), 0, 8)}"
  # The tests use a key store with the prefix tck-, so the kits' own names (data, blobs, tenant-a, ...) find these.
  # Key Vault Standard has no MAC keys (only Managed HSM does), so there's no tck-mac here.
  wrap = ["wrapKey", "unwrapKey"]
  sign = ["sign", "verify"]
  keys = {
    "tck-data"        = { type = "RSA", size = 2048, curve = null, ops = local.wrap } # the kit rotates it
    "tck-other"       = { type = "RSA", size = 2048, curve = null, ops = local.wrap } # re-wrapping, two keys
    "tck-disposable"  = { type = "RSA", size = 2048, curve = null, ops = local.wrap } # the kit disables it
    "tck-server-side" = { type = "RSA", size = 2048, curve = null, ops = local.wrap } # blob encryption scopes
    "tck-blobs"       = { type = "RSA", size = 2048, curve = null, ops = local.wrap } # the blob encryption kit
    "tck-fields"      = { type = "RSA", size = 2048, curve = null, ops = local.wrap } # the document encryption kit
    "tck-parameters"  = { type = "RSA", size = 2048, curve = null, ops = local.wrap } # the parameter encryption kit
    "tck-tenant-a"    = { type = "RSA", size = 2048, curve = null, ops = local.wrap } # the kits' keys per tenant
    "tck-tenant-b"    = { type = "RSA", size = 2048, curve = null, ops = local.wrap }
    "tck-rsa"         = { type = "RSA", size = 2048, curve = null, ops = local.sign }   # PS256, RS256, JWTs
    "tck-p256"        = { type = "EC", size = null, curve = "P-256", ops = local.sign } # ES256, JWTs
    "tck-p384"        = { type = "EC", size = null, curve = "P-384", ops = local.sign } # ES384
  }
}

resource "azurerm_resource_group" "keys" {
  name     = "halyx-keys"
  location = var.location
  tags     = local.tags
}

resource "azurerm_key_vault" "keys" {
  name                       = local.vault
  resource_group_name        = azurerm_resource_group.keys.name
  location                   = var.location
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  purge_protection_enabled   = false # so it can be purged, if it's ever destroyed
  soft_delete_retention_days = 7
  tags                       = local.tags
}

# Whoever applies this creates the keys.
resource "azurerm_role_assignment" "admin" {
  scope                = azurerm_key_vault.keys.id
  role_definition_name = "Key Vault Crypto Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

# The test principal uses every key: wrap, unwrap, sign, read and list.
resource "azurerm_role_assignment" "use" {
  scope                = azurerm_key_vault.keys.id
  role_definition_name = "Key Vault Crypto User"
  principal_id         = var.test_principal
  principal_type       = "ServicePrincipal"
}

resource "time_sleep" "roles" {
  depends_on      = [azurerm_role_assignment.admin, azurerm_role_assignment.use]
  create_duration = "60s" # data-plane role assignments take a while to apply
}

resource "azurerm_key_vault_key" "this" {
  for_each     = local.keys
  name         = each.key
  key_vault_id = azurerm_key_vault.keys.id
  key_type     = each.value.type
  key_size     = each.value.size
  curve        = each.value.curve
  key_opts     = each.value.ops
  tags         = local.tags
  depends_on   = [time_sleep.roles]
}

# What the key store kit does to keys, on those keys only: rotate tck-data and the signing keys, and disable and
# enable every version of tck-disposable.
resource "azurerm_role_assignment" "kit" {
  for_each             = toset(["tck-data", "tck-disposable", "tck-rsa", "tck-p256", "tck-p384"])
  scope                = "${azurerm_key_vault.keys.id}/keys/${each.key}"
  role_definition_name = "Key Vault Crypto Officer"
  principal_id         = var.test_principal
  principal_type       = "ServicePrincipal"
  depends_on           = [azurerm_key_vault_key.this]
}

output "vault_id" {
  description = "For tier 1's keys_vault_id"
  value       = azurerm_key_vault.keys.id
}

output "vault_url" {
  value = azurerm_key_vault.keys.vault_uri
}

output "server_side_key_id" {
  description = "For tier 1's server_side_key_id: versionless, so the encryption scope follows rotations"
  value       = azurerm_key_vault_key.this["tck-server-side"].versionless_id
}
