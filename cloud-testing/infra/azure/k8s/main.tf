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

# The portable orders service on AKS: a cluster with Microsoft Entra Workload ID, a registry for the image, and every
# service the sample uses, in a resource group of the run's own. The pods log in as a user-assigned managed identity
# federated to their Kubernetes service account; it holds only data-plane roles. Entra ID only: shared keys and local
# auth are off everywhere but the registry, whose admin credentials are the cluster's pull secret (assigning AcrPull
# is outside what the test principal may grant).

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
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
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

variable "run_id" {
  type = string
}

variable "node_size" {
  type    = string
  default = "Standard_B2s_v2" # 2 vCPUs, 8 GiB; the subscription doesn't offer the older B2s for AKS
}

variable "namespace" {
  description = "The Kubernetes namespace the service runs in; its service account is federated to the identity"
  type        = string
  default     = "portable-orders"
}

# Credentials: ARM_CLIENT_ID, ARM_CLIENT_SECRET, ARM_TENANT_ID and ARM_SUBSCRIPTION_ID (the test principal).
provider "azurerm" {
  resource_provider_registrations = "none" # the script registers what's needed
  storage_use_azuread             = true
  features {
    resource_group {
      prevent_deletion_if_contains_resources = false
    }
    key_vault {
      purge_soft_delete_on_destroy = true
    }
    app_configuration {
      purge_soft_delete_on_destroy = true
    }
  }
}

data "azurerm_client_config" "current" {}

locals {
  name   = "halyx-${var.run_id}"
  tags   = { halyx-test = var.run_id }
  me     = data.azurerm_client_config.current.object_id
  prefix = "halyx-${var.run_id}" # the parameter stores' prefix
  app    = azurerm_user_assigned_identity.app.principal_id
}

resource "azurerm_resource_group" "run" {
  name     = local.name
  location = var.location
  tags     = local.tags
}

# --- cluster, registry and the workload identity ---------------------------------------------------------------

resource "azurerm_kubernetes_cluster" "aks" {
  name                      = local.name
  location                  = var.location
  resource_group_name       = azurerm_resource_group.run.name
  dns_prefix                = local.name
  sku_tier                  = "Free"
  oidc_issuer_enabled       = true
  workload_identity_enabled = true
  local_account_disabled    = false # the script uses the cluster's local credentials, so no kubelogin is needed

  default_node_pool {
    name            = "system"
    node_count      = 1
    vm_size         = var.node_size
    os_disk_size_gb = 30
    upgrade_settings {
      max_surge = "10%"
    }
  }

  identity {
    type = "SystemAssigned"
  }

  tags = local.tags
}

resource "azurerm_container_registry" "images" {
  name                = replace("halyx${var.run_id}", "-", "")
  resource_group_name = azurerm_resource_group.run.name
  location            = var.location
  sku                 = "Basic"
  admin_enabled       = true
  tags                = local.tags
}

resource "azurerm_user_assigned_identity" "app" {
  name                = "${local.name}-app"
  resource_group_name = azurerm_resource_group.run.name
  location            = var.location
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "app" {
  name                = "portable-orders"
  resource_group_name = azurerm_resource_group.run.name
  parent_id           = azurerm_user_assigned_identity.app.id
  audience            = ["api://AzureADTokenExchange"]
  issuer              = azurerm_kubernetes_cluster.aks.oidc_issuer_url
  subject             = "system:serviceaccount:${var.namespace}:portable-orders"
}

# --- receipts: Blob Storage --------------------------------------------------------------------------------------

resource "azurerm_storage_account" "receipts" {
  name                            = replace("halyx${var.run_id}", "-", "")
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

resource "azurerm_storage_container" "receipts" {
  name               = "receipts"
  storage_account_id = azurerm_storage_account.receipts.id
}

resource "azurerm_role_assignment" "receipts" {
  scope                = azurerm_storage_account.receipts.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = local.app
}

# --- orders: Cosmos DB --------------------------------------------------------------------------------------------

resource "azurerm_cosmosdb_account" "orders" {
  name                         = local.name
  resource_group_name          = azurerm_resource_group.run.name
  location                     = var.location
  offer_type                   = "Standard"
  kind                         = "GlobalDocumentDB"
  local_authentication_enabled = false
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

resource "azurerm_cosmosdb_sql_database" "orders" {
  name                = "portable-orders"
  resource_group_name = azurerm_resource_group.run.name
  account_name        = azurerm_cosmosdb_account.orders.name
}

resource "azurerm_cosmosdb_sql_container" "orders" {
  name                = "orders"
  resource_group_name = azurerm_resource_group.run.name
  account_name        = azurerm_cosmosdb_account.orders.name
  database_name       = azurerm_cosmosdb_sql_database.orders.name
  partition_key_paths = ["/pk"]
  default_ttl         = -1
}

resource "azurerm_cosmosdb_sql_role_assignment" "orders" {
  resource_group_name = azurerm_resource_group.run.name
  account_name        = azurerm_cosmosdb_account.orders.name
  role_definition_id  = "${azurerm_cosmosdb_account.orders.id}/sqlRoleDefinitions/00000000-0000-0000-0000-000000000002"
  principal_id        = local.app
  scope               = azurerm_cosmosdb_account.orders.id
}

# --- order-placed and fulfilment: Service Bus -------------------------------------------------------------------

resource "azurerm_servicebus_namespace" "bus" {
  name                = local.name
  resource_group_name = azurerm_resource_group.run.name
  location            = var.location
  sku                 = "Standard"
  local_auth_enabled  = false
  tags                = local.tags
}

resource "azurerm_servicebus_topic" "placed" {
  name                = "placed"
  namespace_id        = azurerm_servicebus_namespace.bus.id
  default_message_ttl = "PT1H"
}

resource "azurerm_servicebus_subscription" "fulfilment" {
  name                = "fulfilment"
  topic_id            = azurerm_servicebus_topic.placed.id
  max_delivery_count  = 10
  lock_duration       = "PT30S"
  default_message_ttl = "PT1H"
}

resource "azurerm_role_assignment" "bus" {
  scope                = azurerm_servicebus_namespace.bus.id
  role_definition_name = "Azure Service Bus Data Owner" # the narrower sender and receiver roles aren't ones the principal may assign
  principal_id         = local.app
}

# --- settings and secrets: App Configuration and Key Vault ------------------------------------------------------
# The principal writes the run's values, so it gets the data roles too; role assignments take a minute to apply.

resource "azurerm_app_configuration" "settings" {
  name                     = local.name
  resource_group_name      = azurerm_resource_group.run.name
  location                 = var.location
  sku                      = "free"
  local_auth_enabled       = false
  purge_protection_enabled = false
  tags                     = local.tags
}

resource "azurerm_key_vault" "secrets" {
  name                       = local.name
  resource_group_name        = azurerm_resource_group.run.name
  location                   = var.location
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  purge_protection_enabled   = false
  soft_delete_retention_days = 7
  tags                       = local.tags
}

resource "azurerm_role_assignment" "settings" {
  for_each             = { me = local.me, app = local.app }
  scope                = azurerm_app_configuration.settings.id
  role_definition_name = "App Configuration Data Owner"
  principal_id         = each.value
}

resource "azurerm_role_assignment" "secrets" {
  for_each             = { me = local.me, app = local.app }
  scope                = azurerm_key_vault.secrets.id
  role_definition_name = "Key Vault Secrets Officer" # the read-only Secrets User role isn't one the principal may assign
  principal_id         = each.value
}

resource "time_sleep" "data_roles" {
  depends_on      = [azurerm_role_assignment.settings, azurerm_role_assignment.secrets]
  create_duration = "90s"
}

resource "azurerm_app_configuration_key" "max_lines" {
  configuration_store_id = azurerm_app_configuration.settings.id
  key                    = "${local.prefix}/orders/max-lines"
  value                  = "4"
  depends_on             = [time_sleep.data_roles]
}

resource "azurerm_app_configuration_key" "currency" {
  configuration_store_id = azurerm_app_configuration.settings.id
  key                    = "${local.prefix}/orders/currency"
  value                  = "NZD"
  depends_on             = [time_sleep.data_roles]
}

resource "random_password" "signing_key" {
  length  = 32
  special = false
}

resource "azurerm_key_vault_secret" "signing_key" {
  name         = "${local.prefix}--receipt-signing-key"
  value        = random_password.signing_key.result
  key_vault_id = azurerm_key_vault.secrets.id
  depends_on   = [time_sleep.data_roles]
}

# --- order-events and notifications: an Event Grid namespace topic with a pull subscription --------------------

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

resource "azapi_resource" "notifications" {
  type      = "Microsoft.EventGrid/namespaces/topics/eventSubscriptions@2025-02-15"
  name      = "notifications"
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

data "azapi_resource" "events_namespace" {
  type                   = "Microsoft.EventGrid/namespaces@2025-02-15"
  resource_id            = azurerm_eventgrid_namespace.events.id
  response_export_values = { hostname = "properties.topicsConfiguration.hostname" }
}

resource "azurerm_role_assignment" "events" {
  for_each             = toset(["EventGrid Data Sender", "EventGrid Data Receiver"])
  scope                = azurerm_eventgrid_namespace.events.id
  role_definition_name = each.key
  principal_id         = local.app
}

# --- order-audit: Event Hubs' Kafka endpoint --------------------------------------------------------------------

# Event Hubs and Service Bus namespaces share one DNS zone (servicebus.windows.net), so this one has a suffix.
resource "azurerm_eventhub_namespace" "streams" {
  name                         = "${local.name}-streams"
  resource_group_name          = azurerm_resource_group.run.name
  location                     = var.location
  sku                          = "Standard"
  capacity                     = 1
  local_authentication_enabled = false
  tags                         = local.tags
}

resource "azurerm_eventhub" "audit" {
  name              = "audit"
  namespace_id      = azurerm_eventhub_namespace.streams.id
  partition_count   = 2
  message_retention = 1
}

resource "azurerm_role_assignment" "streams" {
  scope                = azurerm_eventhub_namespace.streams.id
  role_definition_name = "Azure Event Hubs Data Owner"
  principal_id         = local.app
}

# --- idempotency: Azure Managed Redis, Entra ID only -------------------------------------------------------------

resource "azurerm_managed_redis" "cache" {
  name                      = local.name
  resource_group_name       = azurerm_resource_group.run.name
  location                  = var.location
  sku_name                  = "Balanced_B0"
  high_availability_enabled = false
  public_network_access     = "Enabled"
  default_database {
    access_keys_authentication_enabled = false
    clustering_policy                  = "EnterpriseCluster"
    client_protocol                    = "Encrypted"
    eviction_policy                    = "NoEviction"
  }
  tags = local.tags
}

resource "azurerm_managed_redis_access_policy_assignment" "app" {
  managed_redis_id = azurerm_managed_redis.cache.id
  object_id        = local.app
}

# --- ledger: Azure Database for PostgreSQL, with the workload identity as its Entra admin -----------------------

resource "azurerm_postgresql_flexible_server" "ledger" {
  name                          = "${local.name}-pg"
  resource_group_name           = azurerm_resource_group.run.name
  location                      = var.location
  version                       = "16"
  sku_name                      = "B_Standard_B1ms"
  storage_mb                    = 32768
  backup_retention_days         = 7
  public_network_access_enabled = true
  authentication {
    active_directory_auth_enabled = true
    password_auth_enabled         = false
    tenant_id                     = data.azurerm_client_config.current.tenant_id
  }
  tags = local.tags
  lifecycle {
    ignore_changes = [zone]
  }
}

resource "azurerm_postgresql_flexible_server_database" "orders" {
  name      = "orders"
  server_id = azurerm_postgresql_flexible_server.ledger.id
}

# The cluster's outbound address is a load balancer's that Azure assigns; allow connections from Azure services.
resource "azurerm_postgresql_flexible_server_firewall_rule" "azure" {
  name             = "azure-services"
  server_id        = azurerm_postgresql_flexible_server.ledger.id
  start_ip_address = "0.0.0.0"
  end_ip_address   = "0.0.0.0"
}

resource "azurerm_postgresql_flexible_server_active_directory_administrator" "app" {
  server_name         = azurerm_postgresql_flexible_server.ledger.name
  resource_group_name = azurerm_resource_group.run.name
  tenant_id           = data.azurerm_client_config.current.tenant_id
  object_id           = local.app
  principal_name      = azurerm_user_assigned_identity.app.name
  principal_type      = "ServicePrincipal"
}

# --- outputs -----------------------------------------------------------------------------------------------------

output "cluster" {
  value = azurerm_kubernetes_cluster.aks.name
}

output "resource_group" {
  value = azurerm_resource_group.run.name
}

# A cluster without Entra ID RBAC has its admin credentials in kube_config; kube_admin_config is empty.
output "kubeconfig" {
  value     = azurerm_kubernetes_cluster.aks.kube_config_raw
  sensitive = true
}

output "registry" {
  value = azurerm_container_registry.images.login_server
}

output "registry_user" {
  value = azurerm_container_registry.images.admin_username
}

output "registry_password" {
  value     = azurerm_container_registry.images.admin_password
  sensitive = true
}

output "client_id" {
  value = azurerm_user_assigned_identity.app.client_id
}

output "signing_key" {
  value     = random_password.signing_key.result
  sensitive = true
}

output "env" {
  description = "The service's environment on this run"
  value = {
    ORDERS_PARAMETER_PREFIX           = local.prefix
    ORDERS_STORAGE_ACCOUNT            = azurerm_storage_account.receipts.name
    ORDERS_RECEIPTS_BUCKET            = azurerm_storage_container.receipts.name
    ORDERS_COSMOS_ENDPOINT            = azurerm_cosmosdb_account.orders.endpoint
    ORDERS_COSMOS_DATABASE            = azurerm_cosmosdb_sql_database.orders.name
    ORDERS_TABLE                      = azurerm_cosmosdb_sql_container.orders.name
    ORDERS_SERVICEBUS_NAMESPACE       = "${azurerm_servicebus_namespace.bus.name}.servicebus.windows.net"
    ORDERS_TOPIC                      = azurerm_servicebus_topic.placed.name
    ORDERS_FULFILMENT_SUBSCRIPTION    = "${azurerm_servicebus_topic.placed.name}/subscriptions/${azurerm_servicebus_subscription.fulfilment.name}"
    ORDERS_APPCONFIG_ENDPOINT         = azurerm_app_configuration.settings.endpoint
    ORDERS_KEYVAULT_URL               = azurerm_key_vault.secrets.vault_uri
    ORDERS_EVENTGRID_ENDPOINT         = "https://${data.azapi_resource.events_namespace.output.hostname}"
    ORDERS_EVENT_BUS                  = azapi_resource.events_topic.name
    ORDERS_NOTIFICATIONS_SUBSCRIPTION = azapi_resource.notifications.name
    ORDERS_KAFKA_BOOTSTRAP_SERVERS    = "${azurerm_eventhub_namespace.streams.name}.servicebus.windows.net:9093"
    ORDERS_AUDIT_TOPIC                = azurerm_eventhub.audit.name
    ORDERS_CACHE_ENDPOINT             = "rediss://${azurerm_managed_redis.cache.hostname}:${azurerm_managed_redis.cache.default_database[0].port}"
    ORDERS_CACHE_USER                 = local.app
    ORDERS_DB_HOST                    = azurerm_postgresql_flexible_server.ledger.fqdn
    ORDERS_DB_USER                    = azurerm_user_assigned_identity.app.name
  }
}
