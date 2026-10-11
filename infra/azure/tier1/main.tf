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

# Tier 1 real-cloud test resources on Azure: Blob Storage, Service Bus, Cosmos DB, Key Vault and App
# Configuration in a resource group of the run's own. Entra ID only: shared keys and local auth are off,
# and the test service principal gets data-plane roles on each resource.

terraform {
  required_version = ">= 1.5"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
  }
}

variable "location" {
  type = string
}

variable "run_id" {
  type = string
}

# Credentials: ARM_CLIENT_ID, ARM_CLIENT_SECRET, ARM_TENANT_ID and ARM_SUBSCRIPTION_ID (the test principal).
provider "azurerm" {
  resource_provider_registrations = "none" # registered once, up front
  storage_use_azuread             = true   # no shared keys
  features {
    resource_group {
      prevent_deletion_if_contains_resources = false # the run's own group; tests add Service Bus entities
    }
    key_vault {
      purge_soft_delete_on_destroy = true # free the name; nothing of a test run is worth recovering
    }
    app_configuration {
      purge_soft_delete_on_destroy = true
    }
  }
}

data "azurerm_client_config" "current" {}

locals {
  name = "cirrus-${var.run_id}"
  tags = { cirrus-test = var.run_id }
  me   = data.azurerm_client_config.current.object_id
}

resource "azurerm_resource_group" "run" {
  name     = local.name
  location = var.location
  tags     = local.tags
}

# --- Blob Storage --------------------------------------------------------------------------------------

resource "azurerm_storage_account" "blobs" {
  name                            = replace("cirrus${var.run_id}", "-", "") # 3-24 lowercase letters and digits
  resource_group_name             = azurerm_resource_group.run.name
  location                        = var.location
  account_tier                    = "Standard"
  account_replication_type        = "LRS"
  account_kind                    = "StorageV2"
  min_tls_version                 = "TLS1_2"
  shared_access_key_enabled       = false
  allow_nested_items_to_be_public = false
  tags                            = local.tags
}

resource "azurerm_storage_container" "tck" {
  name               = "cirrus-tck"
  storage_account_id = azurerm_storage_account.blobs.id
}

resource "azurerm_role_assignment" "blobs" {
  scope                = azurerm_storage_account.blobs.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = local.me
}

# --- Service Bus (Standard: topics, sessions, duplicate detection) -------------------------------------

resource "azurerm_servicebus_namespace" "bus" {
  name                = local.name
  resource_group_name = azurerm_resource_group.run.name
  location            = var.location
  sku                 = "Standard"
  local_auth_enabled  = false
  tags                = local.tags
}

resource "azurerm_role_assignment" "bus" {
  scope                = azurerm_servicebus_namespace.bus.id
  role_definition_name = "Azure Service Bus Data Owner" # send, receive, and manage the tests' entities
  principal_id         = local.me
}

# --- Cosmos DB (NoSQL API, serverless) -----------------------------------------------------------------

resource "azurerm_cosmosdb_account" "docs" {
  name                          = local.name
  resource_group_name           = azurerm_resource_group.run.name
  location                      = var.location
  offer_type                    = "Standard"
  kind                          = "GlobalDocumentDB"
  local_authentication_enabled  = false
  capabilities {
    name = "EnableServerless"
  }
  consistency_policy {
    consistency_level = "Session"
  }
  geo_location {
    location          = var.location
    failover_priority = 0
  }
  tags = local.tags
}

resource "azurerm_cosmosdb_sql_database" "cirrus" {
  name                = "cirrus"
  resource_group_name = azurerm_resource_group.run.name
  account_name        = azurerm_cosmosdb_account.docs.name
}

resource "azurerm_cosmosdb_sql_container" "tables" {
  for_each            = toset(["items", "items-sorted"])
  name                = each.key
  resource_group_name = azurerm_resource_group.run.name
  account_name        = azurerm_cosmosdb_account.docs.name
  database_name       = azurerm_cosmosdb_sql_database.cirrus.name
  partition_key_paths = ["/pk"]
  default_ttl         = -1 # TTL on, per item
}

# Cosmos data-plane access is Cosmos' own RBAC: the built-in Data Contributor role.
resource "azurerm_cosmosdb_sql_role_assignment" "docs" {
  resource_group_name = azurerm_resource_group.run.name
  account_name        = azurerm_cosmosdb_account.docs.name
  role_definition_id  = "${azurerm_cosmosdb_account.docs.id}/sqlRoleDefinitions/00000000-0000-0000-0000-000000000002"
  principal_id        = local.me
  scope               = azurerm_cosmosdb_account.docs.id
}

# --- Key Vault (RBAC) -----------------------------------------------------------------------------------

resource "azurerm_key_vault" "secrets" {
  name                       = local.name # 3-24 characters
  resource_group_name        = azurerm_resource_group.run.name
  location                   = var.location
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  purge_protection_enabled   = false
  soft_delete_retention_days = 7
  tags                       = local.tags
}

resource "azurerm_role_assignment" "secrets" {
  scope                = azurerm_key_vault.secrets.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = local.me
}

# --- App Configuration ------------------------------------------------------------------------------------

resource "azurerm_app_configuration" "parameters" {
  name                     = local.name
  resource_group_name      = azurerm_resource_group.run.name
  location                 = var.location
  sku                      = "free"
  local_auth_enabled       = false
  purge_protection_enabled = false
  tags                     = local.tags
}

resource "azurerm_role_assignment" "parameters" {
  scope                = azurerm_app_configuration.parameters.id
  role_definition_name = "App Configuration Data Owner"
  principal_id         = local.me
}

output "storage_account" {
  value = azurerm_storage_account.blobs.name
}

output "container" {
  value = azurerm_storage_container.tck.name
}

output "servicebus_namespace" {
  value = "${azurerm_servicebus_namespace.bus.name}.servicebus.windows.net"
}

output "cosmos_endpoint" {
  value = azurerm_cosmosdb_account.docs.endpoint
}

output "key_vault_url" {
  value = azurerm_key_vault.secrets.vault_uri
}

output "appconfig_endpoint" {
  value = azurerm_app_configuration.parameters.endpoint
}
