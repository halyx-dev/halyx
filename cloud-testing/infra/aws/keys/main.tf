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

# The KMS keys the real-cloud key tests use, and the test user's permission to use them. Unlike the tiers, this is
# applied once, by an administrator, and kept: KMS keys can only be scheduled for deletion (7 days at least), so
# creating them per run would leave a trail of keys pending deletion behind every run. The run scripts never apply
# or destroy it.
#
#   terraform -chdir=cloud-testing/infra/aws/keys init
#   terraform -chdir=cloud-testing/infra/aws/keys apply -var region=ap-southeast-2 -var test_user=<test IAM user>
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

variable "test_user" {
  type        = string
  description = "The IAM user the real-cloud scripts run as; the keys policy is attached to it"
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
  encryption = [for name, key in local.keys : aws_kms_key.this[name].arn if key.usage == "ENCRYPT_DECRYPT"]
  signing    = [for name, key in local.keys : aws_kms_key.this[name].arn if key.usage == "SIGN_VERIFY"]
}

# The default key policy: the account's IAM policies decide who may use each key.
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

# A customer-managed policy of its own: the test user's inline policies are near their size limit, and this one
# changes with the keys above.
resource "aws_iam_policy" "keys" {
  name        = "halyx-test-keys"
  description = "Use of the Halyx real-cloud test keys (cloud-testing/infra/aws/keys)"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Wrapping and unwrapping data keys; GenerateDataKey for S3, SSM and Secrets Manager encrypting with a key.
        Sid      = "Encrypt"
        Effect   = "Allow"
        Action   = ["kms:Encrypt", "kms:Decrypt", "kms:ReEncryptFrom", "kms:ReEncryptTo", "kms:GenerateDataKey", "kms:DescribeKey"]
        Resource = local.encryption
      },
      {
        # Signatures are verified locally, with the public key.
        Sid      = "Sign"
        Effect   = "Allow"
        Action   = ["kms:Sign", "kms:GetPublicKey", "kms:DescribeKey"]
        Resource = local.signing
      },
      {
        Sid      = "Mac"
        Effect   = "Allow"
        Action   = ["kms:GenerateMac", "kms:VerifyMac", "kms:DescribeKey"]
        Resource = aws_kms_key.this["tck-mac"].arn
      },
      {
        # What the key store kit does to keys: rotate the encryption key, and disable and enable the disposable one.
        Sid      = "KitRotates"
        Effect   = "Allow"
        Action   = ["kms:RotateKeyOnDemand"]
        Resource = aws_kms_key.this["tck-data"].arn
      },
      {
        Sid      = "KitDisables"
        Effect   = "Allow"
        Action   = ["kms:DisableKey", "kms:EnableKey"]
        Resource = aws_kms_key.this["tck-disposable"].arn
      },
      {
        # Listing keys and the health check. Listing describes each key it finds, so the tests list under tck-.
        Sid      = "List"
        Effect   = "Allow"
        Action   = ["kms:ListAliases"]
        Resource = "*"
      },
    ]
  })
}

resource "aws_iam_user_policy_attachment" "keys" {
  user       = var.test_user
  policy_arn = aws_iam_policy.keys.arn
}

output "aliases" {
  description = "Each key's alias name, as the tests name it, and its ARN"
  value       = { for name in keys(local.keys) : name => aws_kms_key.this[name].arn }
}

output "server_side_key" {
  description = "The key ARN for S3 SSE-KMS, SSM SecureString and Secrets Manager"
  value       = aws_kms_key.this["tck-server-side"].arn
}
