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

# Tier 1 real-cloud test resources on Azure: Blob Storage, Service Bus, Cosmos DB, Key Vault, App
# Configuration, an Event Grid namespace and a function app in a resource group of the run's own. Entra ID only: shared keys and local auth are off,
# and the test service principal gets data-plane roles on each resource. The function is invoked with its function key.

terraform {
  required_version = ">= 1.5"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 4.0"
    }
    azapi = {
      source  = "Azure/azapi"
      version = "~> 2.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
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
  name = "halyx-${var.run_id}"
  tags = { halyx-test = var.run_id }
  me   = data.azurerm_client_config.current.object_id
}

resource "azurerm_resource_group" "run" {
  name     = local.name
  location = var.location
  tags     = local.tags
}

# --- Blob Storage --------------------------------------------------------------------------------------

resource "azurerm_storage_account" "blobs" {
  name                            = replace("halyx${var.run_id}", "-", "") # 3-24 lowercase letters and digits
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
  name               = "halyx-tck"
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

# A topic and subscription for the Spring Boot messaging test.
resource "azurerm_servicebus_topic" "spring" {
  name                = "spring"
  namespace_id        = azurerm_servicebus_namespace.bus.id
  default_message_ttl = "PT1H"
}

resource "azurerm_servicebus_subscription" "spring" {
  name                = "billing"
  topic_id            = azurerm_servicebus_topic.spring.id
  max_delivery_count  = 10
  lock_duration       = "PT10S"
  default_message_ttl = "PT1H"
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

resource "azurerm_cosmosdb_sql_database" "halyx" {
  name                = "halyx"
  resource_group_name = azurerm_resource_group.run.name
  account_name        = azurerm_cosmosdb_account.docs.name
}

resource "azurerm_cosmosdb_sql_container" "tables" {
  for_each            = toset(["items", "items-sorted"])
  name                = each.key
  resource_group_name = azurerm_resource_group.run.name
  account_name        = azurerm_cosmosdb_account.docs.name
  database_name       = azurerm_cosmosdb_sql_database.halyx.name
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

# --- Event Grid namespace: a topic with a pull (queue) subscription ------------------------------------------
# azurerm has the namespace but not its topics or their subscriptions, so those go through azapi.

resource "azurerm_eventgrid_namespace" "events" {
  name                = local.name
  resource_group_name = azurerm_resource_group.run.name
  location            = var.location
  sku                 = "Standard"
  capacity            = 1
  tags                = local.tags
}

resource "azapi_resource" "events_topic" {
  type      = "Microsoft.EventGrid/namespaces/topics@2025-02-15"
  name      = "orders"
  parent_id = azurerm_eventgrid_namespace.events.id
  body = {
    properties = {
      inputSchema          = "CloudEventSchemaV1_0"
      publisherType        = "Custom"
      eventRetentionInDays = 1
    }
  }
}

resource "azapi_resource" "events_inbox" {
  type      = "Microsoft.EventGrid/namespaces/topics/eventSubscriptions@2025-02-15"
  name      = "inbox"
  parent_id = azapi_resource.events_topic.id
  body = {
    properties = {
      eventDeliverySchema = "CloudEventSchemaV1_0"
      deliveryConfiguration = {
        deliveryMode = "Queue"
        queue = {
          receiveLockDurationInSeconds = 60
          maxDeliveryCount             = 10
          eventTimeToLive              = "P1D"
        }
      }
    }
  }
}

# The namespace's HTTP hostname, which azurerm doesn't export.
data "azapi_resource" "events_namespace" {
  type                   = "Microsoft.EventGrid/namespaces@2025-02-15"
  resource_id            = azurerm_eventgrid_namespace.events.id
  response_export_values = { hostname = "properties.topicsConfiguration.hostname" }
}

resource "azurerm_role_assignment" "events" {
  for_each             = toset(["EventGrid Data Sender", "EventGrid Data Receiver"])
  scope                = azurerm_eventgrid_namespace.events.id
  role_definition_name = each.key
  principal_id         = local.me
}

# --- Function app -----------------------------------------------------------------------------------------

# The function kit invokes this function: the same function app the emulator tests serve with the Functions host.
# A Basic plan's single instance, so the runs it counts in memory are the same for every invocation. The host reaches
# its storage with the app's managed identity, as shared keys are off.
data "archive_file" "tck" {
  type        = "zip"
  source_dir  = "${path.module}/../../../../halyx-azure/src/test/resources/functions/azure"
  output_path = "${path.module}/.build/tck.zip"
}

resource "azurerm_storage_account" "functions" {
  name                            = replace("halyx${var.run_id}fn", "-", "")
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

resource "azurerm_service_plan" "functions" {
  name                = "${local.name}-functions"
  resource_group_name = azurerm_resource_group.run.name
  location            = var.location
  os_type             = "Linux"
  sku_name            = "B1"
  worker_count        = 1
  tags                = local.tags
}

resource "azurerm_linux_function_app" "tck" {
  name                          = "${local.name}-tck"
  resource_group_name           = azurerm_resource_group.run.name
  location                      = var.location
  service_plan_id               = azurerm_service_plan.functions.id
  storage_account_name          = azurerm_storage_account.functions.name
  storage_uses_managed_identity = true
  zip_deploy_file               = data.archive_file.tck.output_path
  https_only                    = true
  identity {
    type = "SystemAssigned"
  }
  site_config {
    always_on = true
    application_stack {
      python_version = "3.12"
    }
  }
  app_settings = {
    SCM_DO_BUILD_DURING_DEPLOYMENT = "false" # the Python worker provides azure.functions
  }
  tags = local.tags
}

resource "azurerm_role_assignment" "functions_storage" {
  for_each             = toset(["Storage Blob Data Owner", "Storage Queue Data Contributor", "Storage Table Data Contributor"])
  scope                = azurerm_storage_account.functions.id
  role_definition_name = each.key
  principal_id         = azurerm_linux_function_app.tck.identity[0].principal_id
}

data "azurerm_function_app_host_keys" "tck" {
  name                = azurerm_linux_function_app.tck.name
  resource_group_name = azurerm_resource_group.run.name
  depends_on          = [azurerm_role_assignment.functions_storage]
}

output "function_endpoint" {
  value = "https://${azurerm_linux_function_app.tck.default_hostname}"
}

output "function_key" {
  value     = data.azurerm_function_app_host_keys.tck.default_function_key
  sensitive = true
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

output "eventgrid_endpoint" {
  value = "https://${data.azapi_resource.events_namespace.output.hostname}"
}

output "eventgrid_topic" {
  value = azapi_resource.events_topic.name
}

output "eventgrid_subscription" {
  value = azapi_resource.events_inbox.name
}

output "spring_topic" {
  value = azurerm_servicebus_topic.spring.name
}

output "spring_subscription" {
  value = "${azurerm_servicebus_topic.spring.name}/subscriptions/${azurerm_servicebus_subscription.spring.name}"
}
