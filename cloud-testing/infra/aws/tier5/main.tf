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

# Tier 5 real-cloud test resources: cloud logins. An IAM role that the test identity may assume only with the
# run's external ID, and a bucket the role may use. Created and destroyed per run by
# scripts/aws-real-cloud.sh; every resource is tagged halyx-test=<run id>.

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

variable "run_id" {
  type        = string
  description = "Lowercase letters and digits; names every resource of the run"
}

variable "external_id" {
  type        = string
  description = "The external ID the role's trust policy requires, generated for the run"
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      "halyx-test" = var.run_id
    }
  }
}

data "aws_caller_identity" "me" {}

resource "aws_s3_bucket" "blobs" {
  bucket        = "halyx-${var.run_id}-blobs"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "blobs" {
  bucket                  = aws_s3_bucket.blobs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# The test identity may assume the role, but only with the external ID.
resource "aws_iam_role" "orders" {
  name = "halyx-${var.run_id}-orders"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { AWS = data.aws_caller_identity.me.arn }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "sts:ExternalId" = var.external_id } }
    }]
  })
}

resource "aws_iam_role_policy" "orders" {
  name = "bucket"
  role = aws_iam_role.orders.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:ListBucket"], Resource = aws_s3_bucket.blobs.arn },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = "${aws_s3_bucket.blobs.arn}/*" }
    ]
  })
}

output "bucket" {
  value = aws_s3_bucket.blobs.bucket
}

output "role_arn" {
  value = aws_iam_role.orders.arn
}
