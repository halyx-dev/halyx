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

# The Cloud KMS keys the real-cloud key tests use, and who may use them. Applied once, by a project Owner with their
# own credentials (not the test service account), and kept: Cloud KMS key rings and keys can never be deleted, only
# their versions destroyed, so keys made per run would pile up for good. The run scripts never apply or destroy it,
# and prevent_destroy stops a destroy here from destroying every key's versions.
#
#   terraform -chdir=cloud-testing/infra/gcp/keys init
#   terraform -chdir=cloud-testing/infra/gcp/keys apply -var project=<project> -var region=australia-southeast1 \
#       -var service_account=<test service account email>
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

variable "service_account" {
  type        = string
  description = "The test service account the real-cloud scripts impersonate"
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
  # The names match the emulator tests' (GcpKeyStoreConformanceIT), so the real-cloud tests can use the same ones.
  # A Cloud KMS key has one algorithm, so RSA-PSS needs a key of its own.
  keys = {
    "tck-data"        = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # the kit rotates it
    "tck-other"       = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # re-wrapping, two keys
    "tck-disposable"  = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # the kit disables it
    "tck-server-side" = { purpose = "ENCRYPT_DECRYPT", algorithm = "GOOGLE_SYMMETRIC_ENCRYPTION" } # Secret and Parameter Manager
    "tck-rsa"         = { purpose = "ASYMMETRIC_SIGN", algorithm = "RSA_SIGN_PKCS1_2048_SHA256" }  # RS256, JWTs
    "tck-rsa-pss"     = { purpose = "ASYMMETRIC_SIGN", algorithm = "RSA_SIGN_PSS_2048_SHA256" }    # PS256
    "tck-p256"        = { purpose = "ASYMMETRIC_SIGN", algorithm = "EC_SIGN_P256_SHA256" }         # ES256, JWTs
    "tck-p384"        = { purpose = "ASYMMETRIC_SIGN", algorithm = "EC_SIGN_P384_SHA384" }         # ES384
    "tck-mac"         = { purpose = "MAC", algorithm = "HMAC_SHA256" }                             # MACs
  }
  # The key store kit rotates the encryption key and every signing key, and disables and enables tck-disposable.
  kit = ["tck-data", "tck-disposable", "tck-rsa", "tck-rsa-pss", "tck-p256", "tck-p384"]
  sa  = "serviceAccount:${var.service_account}"
}

data "google_project" "this" {}

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

# --- the test service account ---------------------------------------------------------------------------------------

# Every key on the global ring: encrypt and decrypt, sign and MAC, public keys, and reading and listing keys.
resource "google_kms_key_ring_iam_member" "global" {
  for_each = toset([
    "roles/cloudkms.cryptoKeyEncrypterDecrypter", "roles/cloudkms.signerVerifier", "roles/cloudkms.publicKeyViewer",
    "roles/cloudkms.viewer",
  ])
  key_ring_id = google_kms_key_ring.global.id
  role        = each.value
  member      = local.sa
}

resource "google_kms_key_ring_iam_member" "regional" {
  key_ring_id = google_kms_key_ring.regional.id
  role        = "roles/cloudkms.viewer"
  member      = local.sa
}

# Only what the kit does to keys, and only on the keys it does it to. Destroying versions is for pruning the versions
# the kit's rotations leave behind, each of which is billed while it's enabled or disabled.
resource "google_project_iam_custom_role" "kit" {
  role_id     = "halyxTestKeyRotation"
  title       = "Halyx test key rotation"
  description = "Rotating, disabling and enabling the Halyx real-cloud test keys (cloud-testing/infra/gcp/keys)"
  permissions = [
    "cloudkms.cryptoKeys.get", "cloudkms.cryptoKeys.update",
    "cloudkms.cryptoKeyVersions.create", "cloudkms.cryptoKeyVersions.get", "cloudkms.cryptoKeyVersions.list",
    "cloudkms.cryptoKeyVersions.update", "cloudkms.cryptoKeyVersions.destroy",
  ]
}

resource "google_kms_crypto_key_iam_member" "kit" {
  for_each      = toset(local.kit)
  crypto_key_id = google_kms_crypto_key.this[each.key].id
  role          = google_project_iam_custom_role.kit.id
  member        = local.sa
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
