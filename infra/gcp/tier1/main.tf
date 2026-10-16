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
# (so tests never touch the project's (default) database), with the indexes the document store kit queries,
# and an Eventarc Advanced message bus whose enrollment sends every event through a pipeline to a Pub/Sub
# topic. Other Pub/Sub topics, secrets and parameters are created by the tests themselves.

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

# The test service account, which the Eventarc pipeline publishes to Pub/Sub as.
variable "service_account" {
  type = string
}

# Credentials are Application Default Credentials, which impersonate the test service account.
provider "google" {
  project = var.project
  region  = var.region
  default_labels = {
    halyx-test = var.run_id
  }
}

resource "google_storage_bucket" "blobs" {
  name                        = "halyx-${var.run_id}-blobs"
  location                    = upper(var.region)
  force_destroy               = true
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
}

resource "google_firestore_database" "items" {
  name                    = "halyx-${var.run_id}"
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

# --- Eventarc Advanced: bus -> enrollment -> pipeline -> Pub/Sub ---------------------------------------------

resource "google_eventarc_message_bus" "events" {
  location       = var.region
  message_bus_id = "halyx-${var.run_id}"
}

resource "google_pubsub_topic" "events" {
  name = "halyx-${var.run_id}-events"
}

resource "google_pubsub_subscription" "events_inbox" {
  name                       = "halyx-${var.run_id}-events-inbox"
  topic                      = google_pubsub_topic.events.id
  ack_deadline_seconds       = 60
  message_retention_duration = "3600s"
}

# The pipeline publishes to Pub/Sub with an OAuth token for a service account: the test service account, which
# can publish to the topic. The Eventarc service agent mints the token, so it needs Service Account Token Creator
# on that account (granted once, outside Terraform: see docs/cloud-testing/gcp.md).
resource "google_eventarc_pipeline" "events" {
  location    = var.region
  pipeline_id = "halyx-${var.run_id}"
  destinations {
    topic = google_pubsub_topic.events.id
    authentication_config {
      oauth_token {
        service_account = var.service_account
      }
    }
  }
  logging_config {
    log_severity = "ERROR" # delivery failures, such as a refused publish, appear in Cloud Logging
  }
}

resource "google_eventarc_enrollment" "events" {
  location      = var.region
  enrollment_id = "halyx-${var.run_id}"
  message_bus   = google_eventarc_message_bus.events.id
  destination   = google_eventarc_pipeline.events.id
  cel_match     = "true"
}

output "bucket" {
  value = google_storage_bucket.blobs.name
}

output "firestore_database" {
  value = google_firestore_database.items.name
}

output "message_bus" {
  value = google_eventarc_message_bus.events.id
}

output "events_inbox" {
  value = google_pubsub_subscription.events_inbox.name
}
