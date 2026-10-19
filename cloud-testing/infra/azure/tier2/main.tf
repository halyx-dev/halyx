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

# Tier 2 real-cloud test resources on Azure: Azure Database for PostgreSQL (flexible server) and Azure SQL
# Database with Microsoft Entra ID login, the test service principal as Entra admin of both. Throwaway:
# burstable/basic tiers, no geo-redundancy, reachable only from the test machine's address.

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

variable "client_ip" {
  description = "The test machine's public IPv4 address"
  type        = string
}

variable "principal_name" {
  description = "Display name of the test service principal: its PostgreSQL role name"
  type        = string
  default     = "halyx-test"
}

variable "pg_admin_password" {
  description = "Generated per run by the script and passed as TF_VAR_pg_admin_password"
  type        = string
  sensitive   = true
}

# Credentials: ARM_CLIENT_ID, ARM_CLIENT_SECRET, ARM_TENANT_ID and ARM_SUBSCRIPTION_ID (the test principal).
provider "azurerm" {
  resource_provider_registrations = "none" # the script registers what's needed
  features {
    resource_group {
      prevent_deletion_if_contains_resources = false
    }
  }
}

data "azurerm_client_config" "current" {}

locals {
  name = "halyx-${var.run_id}"
  tags = { halyx-test = var.run_id }
  me   = data.azurerm_client_config.current
}

resource "azurerm_resource_group" "run" {
  name     = local.name
  location = var.location
  tags     = local.tags
}

# --- PostgreSQL flexible server ---------------------------------------------------------------------------

resource "azurerm_postgresql_flexible_server" "pg" {
  name                          = "${local.name}-pg"
  resource_group_name           = azurerm_resource_group.run.name
  location                      = var.location
  version                       = "16"
  sku_name                      = "B_Standard_B1ms"
  storage_mb                    = 32768
  backup_retention_days         = 7
  public_network_access_enabled = true
  administrator_login           = "halyx_admin"
  administrator_password        = var.pg_admin_password
  authentication {
    active_directory_auth_enabled = true
    password_auth_enabled         = true # for the admin role the tests use to check a token is refused
    tenant_id                     = local.me.tenant_id
  }
  tags = local.tags

  lifecycle {
    ignore_changes = [zone] # Azure picks one
  }
}

resource "azurerm_postgresql_flexible_server_database" "app" {
  name      = "app"
  server_id = azurerm_postgresql_flexible_server.pg.id
}

resource "azurerm_postgresql_flexible_server_firewall_rule" "client" {
  name             = "test-machine"
  server_id        = azurerm_postgresql_flexible_server.pg.id
  start_ip_address = var.client_ip
  end_ip_address   = var.client_ip
}

resource "azurerm_postgresql_flexible_server_active_directory_administrator" "me" {
  server_name         = azurerm_postgresql_flexible_server.pg.name
  resource_group_name = azurerm_resource_group.run.name
  tenant_id           = local.me.tenant_id
  object_id           = local.me.object_id
  principal_name      = var.principal_name
  principal_type      = "ServicePrincipal"
}

# --- Azure SQL Database (Entra ID only) ------------------------------------------------------------------

resource "azurerm_mssql_server" "sql" {
  name                          = "${local.name}-sql"
  resource_group_name           = azurerm_resource_group.run.name
  location                      = var.location
  version                       = "12.0"
  minimum_tls_version           = "1.2"
  public_network_access_enabled = true
  azuread_administrator {
    login_username              = var.principal_name
    object_id                   = local.me.object_id
    tenant_id                   = local.me.tenant_id
    azuread_authentication_only = true # SQL logins are refused
  }
  tags = local.tags
}

resource "azurerm_mssql_database" "app" {
  name      = "app"
  server_id = azurerm_mssql_server.sql.id
  sku_name  = "Basic"
  tags      = local.tags
}

resource "azurerm_mssql_firewall_rule" "client" {
  name             = "test-machine"
  server_id        = azurerm_mssql_server.sql.id
  start_ip_address = var.client_ip
  end_ip_address   = var.client_ip
}

output "postgres_host" {
  value = azurerm_postgresql_flexible_server.pg.fqdn
}

output "sql_host" {
  value = azurerm_mssql_server.sql.fully_qualified_domain_name
}

output "principal_name" {
  value = var.principal_name
}
