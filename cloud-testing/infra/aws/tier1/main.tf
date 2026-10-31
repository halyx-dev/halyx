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

# Tier 1 real-cloud test resources: everything reachable over public AWS APIs. Created and destroyed per run
# by scripts/aws-real-cloud.sh; every resource is tagged halyx-test=<run id>. SNS topics, SQS queues,
# parameters and secrets are created (and deleted) by the tests themselves, under the run id.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.4"
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
      "halyx-test" = var.run_id
    }
  }
}

resource "aws_s3_bucket" "blobs" {
  bucket        = "halyx-${var.run_id}-blobs"
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
  name         = "halyx-${var.run_id}-items"
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
    attribute_name = "halyx_expires"
    enabled        = true
  }
}

resource "aws_dynamodb_table" "items_sorted" {
  name         = "halyx-${var.run_id}-items-sorted"
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
    attribute_name = "halyx_expires"
    enabled        = true
  }
}

# --- event bus ------------------------------------------------------------------------------------------

# The event bus kit publishes to this bus; a rule routes its events (source /halyx-tck/...) to a queue it reads.
resource "aws_cloudwatch_event_bus" "events" {
  name = "halyx-${var.run_id}-events"
}

resource "aws_sqs_queue" "events_inbox" {
  name                       = "halyx-${var.run_id}-events-inbox"
  visibility_timeout_seconds = 30
  message_retention_seconds  = 3600
}

resource "aws_cloudwatch_event_rule" "tck" {
  name           = "halyx-${var.run_id}-tck"
  event_bus_name = aws_cloudwatch_event_bus.events.name
  event_pattern  = jsonencode({ source = [{ prefix = "/halyx-tck/" }] })
}

resource "aws_cloudwatch_event_target" "inbox" {
  rule           = aws_cloudwatch_event_rule.tck.name
  event_bus_name = aws_cloudwatch_event_bus.events.name
  target_id      = "inbox"
  arn            = aws_sqs_queue.events_inbox.arn
}

# EventBridge may send to the queue only for this rule.
resource "aws_sqs_queue_policy" "events_inbox" {
  queue_url = aws_sqs_queue.events_inbox.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "events.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.events_inbox.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_cloudwatch_event_rule.tck.arn } }
    }]
  })
}

# --- Messaging for the Spring Boot test: a topic delivering raw messages to a queue ---------------------

resource "aws_sns_topic" "spring" {
  name = "halyx-${var.run_id}-spring"
}

resource "aws_sqs_queue" "spring" {
  name                       = "halyx-${var.run_id}-spring"
  visibility_timeout_seconds = 10
  message_retention_seconds  = 3600
}

# SNS may send to the queue only from this topic.
resource "aws_sqs_queue_policy" "spring" {
  queue_url = aws_sqs_queue.spring.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.spring.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_sns_topic.spring.arn } }
    }]
  })
}

resource "aws_sns_topic_subscription" "spring" {
  topic_arn            = aws_sns_topic.spring.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.spring.arn
  raw_message_delivery = true
  depends_on           = [aws_sqs_queue_policy.spring]
}

# --- function ---------------------------------------------------------------------------------------------

# The function kit invokes this function: the same file the emulator tests deploy. Lambda may run invocations on
# several instances, so the runs it counts are kept in a table rather than in memory.
data "archive_file" "tck" {
  type        = "zip"
  source_file = "${path.module}/../../../../halyx-aws/src/test/resources/functions/lambda/tck.py"
  output_path = "${path.module}/.build/tck.zip"
}

resource "aws_dynamodb_table" "function_runs" {
  name         = "halyx-${var.run_id}-function-runs"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"

  attribute {
    name = "id"
    type = "S"
  }
}

resource "aws_iam_role" "function" {
  name = "halyx-${var.run_id}-function"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "lambda.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "function_logs" {
  role       = aws_iam_role.function.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "function_runs" {
  role = aws_iam_role.function.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["dynamodb:UpdateItem", "dynamodb:GetItem"], Resource = aws_dynamodb_table.function_runs.arn }]
  })
}

resource "aws_lambda_function" "tck" {
  function_name    = "halyx-${var.run_id}-tck"
  role             = aws_iam_role.function.arn
  runtime          = "python3.13"
  handler          = "tck.handler"
  filename         = data.archive_file.tck.output_path
  source_code_hash = data.archive_file.tck.output_base64sha256
  timeout          = 60
  environment {
    variables = { COUNTS_TABLE = aws_dynamodb_table.function_runs.name }
  }
  depends_on = [aws_iam_role_policy_attachment.function_logs, aws_iam_role_policy.function_runs]
}

output "function" {
  value = aws_lambda_function.tck.function_name
}

output "event_bus" {
  value = aws_cloudwatch_event_bus.events.name
}

output "events_inbox" {
  value = aws_sqs_queue.events_inbox.name
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

output "spring_topic" {
  value = aws_sns_topic.spring.arn
}

output "spring_queue" {
  value = aws_sqs_queue.spring.url
}
