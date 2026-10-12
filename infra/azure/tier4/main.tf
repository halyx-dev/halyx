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

# Tier 4 real-cloud test resources on Azure: an Event Hubs namespace whose Kafka endpoint the stream kit uses,
# with Microsoft Entra ID authentication only (shared access keys off). Throwaway: Standard tier, one
# throughput unit. A Standard namespace stopped accepting new event hubs after about twenty had been created in a
# run, deleted or not, so the kit spreads its topics over three namespaces.

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
  name = "halyx-${var.run_id}"
  tags = { halyx-test = var.run_id }
}

resource "azurerm_resource_group" "run" {
  name     = local.name
  location = var.location
  tags     = local.tags
}

resource "azurerm_eventhub_namespace" "streams" {
  count                        = 3
  name                         = "${local.name}-${count.index}"
  resource_group_name          = azurerm_resource_group.run.name
  location                     = var.location
  sku                          = "Standard" # the lowest tier with a Kafka endpoint
  capacity                     = 1
  local_authentication_enabled = false # Entra ID only
  tags                         = local.tags
}

# The service principal may send, receive and manage event hubs (topics) through the Kafka endpoint.
resource "azurerm_role_assignment" "data_owner" {
  count                = 3
  scope                = azurerm_eventhub_namespace.streams[count.index].id
  role_definition_name = "Azure Event Hubs Data Owner"
  principal_id         = data.azurerm_client_config.current.object_id
}

output "eventhubs_namespaces" {
  value = join(",", [for ns in azurerm_eventhub_namespace.streams : "${ns.name}.servicebus.windows.net"])
}
