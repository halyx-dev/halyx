# Tier 1 real-cloud test resources: everything reachable over public AWS APIs. Created and destroyed per run
# by scripts/aws-real-cloud.sh; every resource is tagged cirrus-test=<run id>. SNS topics, SQS queues,
# parameters and secrets are created (and deleted) by the tests themselves, under the run id.

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

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      "cirrus-test" = var.run_id
    }
  }
}

resource "aws_s3_bucket" "blobs" {
  bucket        = "cirrus-${var.run_id}-blobs"
  force_destroy = true # delete every object on destroy
}

resource "aws_s3_bucket_public_access_block" "blobs" {
  bucket                  = aws_s3_bucket.blobs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# The document store TCK's two tables: keyed by pk, and by pk + sk; both with the TCK's indexes.
resource "aws_dynamodb_table" "items" {
  name         = "cirrus-${var.run_id}-items"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"

  attribute {
    name = "pk"
    type = "S"
  }
  attribute {
    name = "status"
    type = "S"
  }
  attribute {
    name = "placedAt"
    type = "S"
  }
  attribute {
    name = "owner"
    type = "S"
  }
  global_secondary_index {
    name            = "by-status"
    hash_key        = "status"
    range_key       = "placedAt"
    projection_type = "ALL"
  }
  global_secondary_index {
    name            = "by-owner"
    hash_key        = "owner"
    projection_type = "ALL"
  }
  ttl {
    attribute_name = "cirrus_expires"
    enabled        = true
  }
}

resource "aws_dynamodb_table" "items_sorted" {
  name         = "cirrus-${var.run_id}-items-sorted"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }
  attribute {
    name = "sk"
    type = "S"
  }
  attribute {
    name = "status"
    type = "S"
  }
  attribute {
    name = "placedAt"
    type = "S"
  }
  global_secondary_index {
    name            = "by-status"
    hash_key        = "status"
    range_key       = "placedAt"
    projection_type = "ALL"
  }
  ttl {
    attribute_name = "cirrus_expires"
    enabled        = true
  }
}

output "bucket" {
  value = aws_s3_bucket.blobs.id
}

output "items_table" {
  value = aws_dynamodb_table.items.name
}

output "items_sorted_table" {
  value = aws_dynamodb_table.items_sorted.name
}
