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

# Tier 5 real-cloud test resources: cloud logins. A storage account whose data plane the test service principal
# may use, logging in with its secret or with a federated token. Created and destroyed per run by
# scripts/azure-real-cloud.sh; everything is in the run's resource group, tagged halyx-test=<run id>.

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

provider "azurerm" {
  resource_provider_registrations = "none"
  storage_use_azuread             = true # no shared keys
  features {}
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

resource "azurerm_storage_account" "blobs" {
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

resource "azurerm_storage_container" "logins" {
  name               = "halyx-logins"
  storage_account_id = azurerm_storage_account.blobs.id
}

resource "azurerm_role_assignment" "blobs" {
  scope                = azurerm_storage_account.blobs.id
  role_definition_name = "Storage Blob Data Contributor"
  principal_id         = data.azurerm_client_config.current.object_id
}

output "storage_account" {
  value = azurerm_storage_account.blobs.name
}

output "container" {
  value = azurerm_storage_container.logins.name
}
