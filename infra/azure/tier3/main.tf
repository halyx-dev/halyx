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

# Tier 3 real-cloud test resources on Azure: Azure Managed Redis with Microsoft Entra ID authentication only
# (access keys off), on its public TLS endpoint. Throwaway: the smallest Balanced SKU, no high availability.

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
  resource_provider_registrations = "none" # the script registers what's needed
  features {
    resource_group {
      prevent_deletion_if_contains_resources = false
    }
  }
}

data "azurerm_client_config" "current" {}

locals {
  name = "cirrus-${var.run_id}"
  tags = { cirrus-test = var.run_id }
}

resource "azurerm_resource_group" "run" {
  name     = local.name
  location = var.location
  tags     = local.tags
}

resource "azurerm_managed_redis" "cache" {
  name                      = local.name
  resource_group_name       = azurerm_resource_group.run.name
  location                  = var.location
  sku_name                  = "Balanced_B0"
  high_availability_enabled = false
  public_network_access     = "Enabled"
  default_database {
    access_keys_authentication_enabled = false              # Entra ID only
    clustering_policy                  = "EnterpriseCluster" # one endpoint, no redirects to node addresses
    client_protocol                    = "Encrypted"
    eviction_policy                    = "NoEviction"
  }
  tags = local.tags
}

# The service principal may use the cache's data plane ("default" access policy).
resource "azurerm_managed_redis_access_policy_assignment" "me" {
  managed_redis_id = azurerm_managed_redis.cache.id
  object_id        = data.azurerm_client_config.current.object_id
}

output "cache_host" {
  value = azurerm_managed_redis.cache.hostname
}

output "cache_port" {
  value = azurerm_managed_redis.cache.default_database[0].port
}
