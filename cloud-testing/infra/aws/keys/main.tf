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

# The KMS keys the real-cloud key tests use. Unlike the tiers, this is applied once, by an administrator, and kept:
# KMS keys can only be scheduled for deletion (7 days at least), so creating them per run would leave a trail of keys
# pending deletion behind every run. The run scripts never apply or destroy it.
#
#   terraform -chdir=cloud-testing/infra/aws/keys init
#   terraform -chdir=cloud-testing/infra/aws/keys apply -var region=ap-southeast-2
#
# It creates no IAM: the test user's permissions are managed outside this project. test-user-policy.json, next to
# this file, holds the statements the test user needs to use the keys; it names them by alias, so it doesn't change
# when a key is created again.
#
# The keys are tagged halyx-keys=long-lived and never halyx-test: every run may delete keys tagged halyx-test (see
# infra/aws/k8s/test-identity-policy.json), and the teardown checks list them. Their aliases start tck-, outside
# the halyx-* aliases a run may create and delete.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
  }
}

variable "region" {
  type = string
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      "halyx-keys" = "long-lived"
    }
  }
}

locals {
  # The tests use a key store with the prefix tck-, so the kits' own names (data, blobs, tenant-a, ...) find these.
  keys = {
    "tck-data"        = { spec = "SYMMETRIC_DEFAULT", usage = "ENCRYPT_DECRYPT", purpose = "the encryption key; the key store kit rotates it on demand" }
    "tck-other"       = { spec = "SYMMETRIC_DEFAULT", usage = "ENCRYPT_DECRYPT", purpose = "a second encryption key, for re-wrapping and data sealed for two keys" }
    "tck-disposable"  = { spec = "SYMMETRIC_DEFAULT", usage = "ENCRYPT_DECRYPT", purpose = "disabled and enabled again by the key store kit" }
    "tck-server-side" = { spec = "SYMMETRIC_DEFAULT", usage = "ENCRYPT_DECRYPT", purpose = "server-side encryption: S3 SSE-KMS, SSM SecureString, Secrets Manager" }
    "tck-blobs"       = { spec = "SYMMETRIC_DEFAULT", usage = "ENCRYPT_DECRYPT", purpose = "the blob encryption kit's key" }
    "tck-fields"      = { spec = "SYMMETRIC_DEFAULT", usage = "ENCRYPT_DECRYPT", purpose = "the document encryption kit's key" }
    "tck-parameters"  = { spec = "SYMMETRIC_DEFAULT", usage = "ENCRYPT_DECRYPT", purpose = "the parameter encryption kit's key" }
    "tck-tenant-a"    = { spec = "SYMMETRIC_DEFAULT", usage = "ENCRYPT_DECRYPT", purpose = "the encryption kits' first key per tenant" }
    "tck-tenant-b"    = { spec = "SYMMETRIC_DEFAULT", usage = "ENCRYPT_DECRYPT", purpose = "the encryption kits' second key per tenant" }
    "tck-rsa"         = { spec = "RSA_2048", usage = "SIGN_VERIFY", purpose = "RSA-PSS and PKCS#1 signing, and JWTs" }
    "tck-p256"        = { spec = "ECC_NIST_P256", usage = "SIGN_VERIFY", purpose = "ES256 signing, and JWTs" }
    "tck-p384"        = { spec = "ECC_NIST_P384", usage = "SIGN_VERIFY", purpose = "ES384 signing" }
    "tck-mac"         = { spec = "HMAC_256", usage = "GENERATE_VERIFY_MAC", purpose = "MACs" }
  }
}

# The default key policy: the account's IAM policies, managed outside this project, decide who may use each key.
resource "aws_kms_key" "this" {
  for_each                 = local.keys
  description              = "Halyx real-cloud tests: ${each.value.purpose}"
  customer_master_key_spec = each.value.spec
  key_usage                = each.value.usage
  deletion_window_in_days  = 7 # the shortest KMS allows, if this is ever destroyed
  # is_enabled stays true: if a run stops between disabling tck-disposable and enabling it again, an apply
  # enables it.
}

resource "aws_kms_alias" "this" {
  for_each      = local.keys
  name          = "alias/${each.key}"
  target_key_id = aws_kms_key.this[each.key].key_id
}

output "aliases" {
  description = "Each key's alias name, as the tests name it, and its ARN"
  value       = { for name in keys(local.keys) : name => aws_kms_key.this[name].arn }
}

output "server_side_key" {
  description = "The key ARN for S3 SSE-KMS, SSM SecureString and Secrets Manager"
  value       = aws_kms_key.this["tck-server-side"].arn
}
