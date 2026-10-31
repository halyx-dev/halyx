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
# an Eventarc Advanced message bus whose enrollment sends every event through a pipeline to a Pub/Sub topic,
# and a Cloud Run function. Other Pub/Sub topics, secrets and parameters are created by the tests themselves.

terraform {
  required_version = ">= 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
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

# --- Messaging for the Spring Boot test ---------------------------------------------------------------

resource "google_pubsub_topic" "spring" {
  name = "halyx-${var.run_id}-spring"
}

resource "google_pubsub_subscription" "spring" {
  name                       = "halyx-${var.run_id}-spring"
  topic                      = google_pubsub_topic.spring.id
  ack_deadline_seconds       = 20
  message_retention_duration = "3600s"
}

# --- function ---------------------------------------------------------------------------------------------

# The function kit invokes this function: the same file the emulator tests serve with the Functions Framework.
# One instance taking one request at a time, so the runs it counts in memory are the same for every invocation.
data "archive_file" "tck" {
  type        = "zip"
  output_path = "${path.module}/.build/tck.zip"
  source {
    content  = file("${path.module}/../../../../halyx-gcp/src/test/resources/functions/gcp/main.py")
    filename = "main.py"
  }
  source {
    content  = "functions-framework==3.*\n"
    filename = "requirements.txt"
  }
}

resource "google_storage_bucket" "functions" {
  name                        = "halyx-${var.run_id}-functions"
  location                    = upper(var.region)
  force_destroy               = true
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
}

resource "google_storage_bucket_object" "tck" {
  name   = "tck-${data.archive_file.tck.output_md5}.zip"
  bucket = google_storage_bucket.functions.name
  source = data.archive_file.tck.output_path
}

resource "google_cloudfunctions2_function" "tck" {
  name     = "halyx-${var.run_id}-tck"
  location = var.region
  build_config {
    runtime     = "python312"
    entry_point = "tck"
    source {
      storage_source {
        bucket = google_storage_bucket.functions.name
        object = google_storage_bucket_object.tck.name
      }
    }
  }
  service_config {
    min_instance_count               = 0
    max_instance_count               = 1
    max_instance_request_concurrency = 1
    timeout_seconds                  = 60
    available_memory                 = "256M"
    ingress_settings                 = "ALLOW_ALL"
    service_account_email            = var.service_account
  }
}

# Only the test service account may invoke it, with a Google ID token.
resource "google_cloud_run_service_iam_member" "tck_invoker" {
  location = var.region
  service  = google_cloudfunctions2_function.tck.service_config[0].service
  role     = "roles/run.invoker"
  member   = "serviceAccount:${var.service_account}"
}

output "function" {
  value = google_cloudfunctions2_function.tck.name
}

output "function_url" {
  value = google_cloudfunctions2_function.tck.service_config[0].uri
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

output "spring_topic" {
  value = google_pubsub_topic.spring.name
}

output "spring_subscription" {
  value = google_pubsub_subscription.spring.name
}
