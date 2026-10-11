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

# Tier 1 real-cloud test resources on GCP: a Cloud Storage bucket and a Firestore database of the run's own
# (so tests never touch the project's (default) database), with the indexes the document store kit queries.
# Pub/Sub topics, secrets and parameters are created by the tests themselves.

terraform {
  required_version = ">= 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
}

variable "project" {
  type = string
}

variable "region" {
  type = string
}

variable "run_id" {
  type = string
}

# Credentials are Application Default Credentials, which impersonate the test service account.
provider "google" {
  project = var.project
  region  = var.region
  default_labels = {
    cirrus-test = var.run_id
  }
}

resource "google_storage_bucket" "blobs" {
  name                        = "cirrus-${var.run_id}-blobs"
  location                    = upper(var.region)
  force_destroy               = true
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
}

resource "google_firestore_database" "items" {
  name                    = "cirrus-${var.run_id}"
  location_id             = var.region
  type                    = "FIRESTORE_NATIVE"
  deletion_policy         = "DELETE"
  delete_protection_state = "DELETE_PROTECTION_DISABLED"
}

# Index queries are collection-group queries on the table's name: equality on the partition attribute,
# ordered by the sort attribute (then the document id) in either direction.
locals {
  by_status = {
    "items-asc"         = { collection = "items", order = "ASCENDING" }
    "items-desc"        = { collection = "items", order = "DESCENDING" }
    "items-sorted-asc"  = { collection = "items-sorted", order = "ASCENDING" }
    "items-sorted-desc" = { collection = "items-sorted", order = "DESCENDING" }
  }
}

resource "google_firestore_index" "by_status" {
  for_each    = local.by_status
  database    = google_firestore_database.items.name
  collection  = each.value.collection
  query_scope = "COLLECTION_GROUP"

  fields {
    field_path = "status"
    order      = "ASCENDING"
  }
  fields {
    field_path = "placedAt"
    order      = each.value.order
  }
}

# by-owner has no sort attribute: equality on owner, ordered by document id. Collection-group queries need a
# collection-group scoped single-field index, which Firestore doesn't create automatically.
resource "google_firestore_field" "owner" {
  database   = google_firestore_database.items.name
  collection = "items"
  field      = "owner"

  index_config {
    indexes {
      order       = "ASCENDING"
      query_scope = "COLLECTION_GROUP"
    }
    indexes {
      order       = "ASCENDING"
      query_scope = "COLLECTION"
    }
    indexes {
      order       = "DESCENDING"
      query_scope = "COLLECTION"
    }
  }
}

output "bucket" {
  value = google_storage_bucket.blobs.name
}

output "firestore_database" {
  value = google_firestore_database.items.name
}
