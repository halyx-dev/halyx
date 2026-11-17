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

# The Cloud KMS keys the real-cloud key tests use. Applied once, by a project Owner with their
# own credentials (not the test service account), and kept: Cloud KMS key rings and keys can never be deleted, only
# their versions destroyed, so keys made per run would pile up for good. The run scripts never apply or destroy it,
# and prevent_destroy stops a destroy here from destroying every key's versions.
#
#   terraform -chdir=cloud-testing/infra/gcp/keys init
#   terraform -chdir=cloud-testing/infra/gcp/keys apply -var project=<project> -var region=australia-southeast1
#
# It grants the test service account nothing: test identities and their permissions are managed outside this
# project. docs/cloud-testing/gcp.md lists the roles it needs on the key rings and keys, and
# test-key-rotation-role.yaml, next to this file, is the custom role for what the key store kit does to keys. Only
# Google's own service agents get a role here, so Cloud Storage, Secret Manager and Parameter Manager can use
# tck-server-side.
#
# Two key rings, both named halyx-tck: a global one with every key, and one in the region with only tck-server-side,
# since a customer-managed key must be in the location of what it protects. Secret Manager's automatic replication
# and global parameters use the global key; the run's bucket and regional parameters use the regional one.

terraform {
  required_version = ">= 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    google-beta = {
      source  = "hashicorp/google-beta"
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

provider "google" {
  project = var.project
  region  = var.region
}

provider "google-beta" {
  project = var.project
  region  = var.region
}

locals {
  # The tests use a key store with the prefix tck-, so the kits' own names (data, blobs, tenant-a, ...) find these.
  # A Cloud KMS key has one algorithm, so RSA-PSS needs a key of its own.
  keys = {
    "tck-data"        = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # the kit rotates it
    "tck-other"       = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # re-wrapping, two keys
    "tck-disposable"  = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # the kit disables it
    "tck-server-side" = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # Secret and Parameter Manager
    "tck-blobs"       = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # the blob encryption kit
    "tck-fields"      = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # the document encryption kit
    "tck-parameters"  = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # the parameter encryption kit
    "tck-tenant-a"    = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # the kits' keys per tenant
    "tck-tenant-b"    = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" }
    "tck-rsa"         = { purpose = "ASYMMETRIC_SIGN", algorithm = "RSA_SIGN_PKCS1_2048_SHA256" } # RS256, JWTs
    "tck-rsa-pss"     = { purpose = "ASYMMETRIC_SIGN", algorithm = "RSA_SIGN_PSS_2048_SHA256" }   # PS256
    "tck-p256"        = { purpose = "ASYMMETRIC_SIGN", algorithm = "EC_SIGN_P256_SHA256" }        # ES256, JWTs
    "tck-p384"        = { purpose = "ASYMMETRIC_SIGN", algorithm = "EC_SIGN_P384_SHA384" }        # ES384
    "tck-mac"         = { purpose = "MAC", algorithm = "HMAC_SHA256" }                            # MACs
  }
}

resource "google_kms_key_ring" "global" {
  name     = "halyx-tck"
  location = "global"
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_kms_key_ring" "regional" {
  name     = "halyx-tck"
  location = var.region
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_kms_crypto_key" "this" {
  for_each                   = local.keys
  name                       = each.key
  key_ring                   = google_kms_key_ring.global.id
  purpose                    = each.value.purpose
  destroy_scheduled_duration = "86400s" # the shortest Cloud KMS allows, for versions the kit rotated out
  labels                     = { halyx-keys = "long-lived" }
  version_template {
    algorithm = each.value.algorithm
  }
  lifecycle {
    prevent_destroy = true
  }
}

resource "google_kms_crypto_key" "regional_server_side" {
  name                       = "tck-server-side"
  key_ring                   = google_kms_key_ring.regional.id
  purpose                    = "ENCRYPT_DECRYPT"
  destroy_scheduled_duration = "86400s"
  labels                     = { halyx-keys = "long-lived" }
  lifecycle {
    prevent_destroy = true
  }
}

# --- the services that encrypt with tck-server-side -----------------------------------------------------------------

data "google_storage_project_service_account" "gcs" {}

resource "google_project_service_identity" "secretmanager" {
  provider = google-beta
  service  = "secretmanager.googleapis.com"
}

resource "google_project_service_identity" "parametermanager" {
  provider = google-beta
  service  = "parametermanager.googleapis.com"
}

resource "google_kms_crypto_key_iam_member" "global_server_side" {
  for_each = {
    secretmanager    = google_project_service_identity.secretmanager.member
    parametermanager = google_project_service_identity.parametermanager.member
  }
  crypto_key_id = google_kms_crypto_key.this["tck-server-side"].id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = each.value
}

resource "google_kms_crypto_key_iam_member" "regional_server_side" {
  for_each = {
    gcs              = data.google_storage_project_service_account.gcs.member
    parametermanager = google_project_service_identity.parametermanager.member
  }
  crypto_key_id = google_kms_crypto_key.regional_server_side.id
  role          = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  member        = each.value
}

output "global_key_ring" {
  description = "The key store location for the key tests"
  value       = google_kms_key_ring.global.id
}

output "server_side_key" {
  description = "For Secret Manager and global parameters"
  value       = google_kms_crypto_key.this["tck-server-side"].id
}

output "regional_server_side_key" {
  description = "For the run's bucket and regional parameters"
  value       = google_kms_crypto_key.regional_server_side.id
}
