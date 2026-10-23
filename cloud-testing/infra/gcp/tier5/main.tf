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

# Tier 5 real-cloud test resources: cloud logins. A bucket that the login target service account may use. The
# target is a standing service account the test identity may impersonate (roles/iam.serviceAccountTokenCreator
# on it), set up once; see docs/cloud-testing/gcp.md. Created and destroyed per run by scripts/gcp-real-cloud.sh.

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

variable "login_target" {
  type        = string
  description = "The email of the service account the tests impersonate"
}

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

resource "google_storage_bucket_iam_member" "target" {
  bucket = google_storage_bucket.blobs.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${var.login_target}"
}

output "bucket" {
  value = google_storage_bucket.blobs.name
}
