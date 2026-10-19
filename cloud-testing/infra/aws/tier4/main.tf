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

# Tier 4 real-cloud test resources: an MSK Serverless cluster (IAM access control only) and a small runner VM
# in the same VPC that runs the tests. MSK Serverless has no public endpoint, and Kafka clients connect to each
# broker by its advertised name, so an SSH tunnel can't stand in for the runner. SSH to the runner is open only
# to the test machine's address, with a key pair generated for the run. Plus a bucket for claim checks.

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
  type = string
}

variable "client_cidr" {
  description = "Address the script connects to the runner from, e.g. 203.0.113.7/32"
  type        = string
}

variable "ssh_public_key" {
  description = "Public half of the key pair generated for the run"
  type        = string
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      halyx-test = var.run_id
    }
  }
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
}

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

# --- runner --------------------------------------------------------------------------------------------

resource "aws_security_group" "runner" {
  name        = "halyx-${var.run_id}-runner"
  description = "Halyx real-cloud tests: SSH from the test machine only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.client_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_key_pair" "runner" {
  key_name   = "halyx-${var.run_id}"
  public_key = var.ssh_public_key
}

# No instance profile: the script hands the tests temporary credentials of the test identity for the run.
resource "aws_instance" "runner" {
  ami                         = data.aws_ssm_parameter.al2023_arm64.value
  instance_type               = "t4g.small" # the test JVM needs more than t4g.micro's 1 GiB
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.runner.id]
  key_name                    = aws_key_pair.runner.key_name
  associate_public_ip_address = true
  user_data                   = "#!/bin/bash\ndnf install -y java-21-amazon-corretto-headless rsync\ntouch /var/tmp/halyx-ready\n"

  metadata_options {
    http_tokens = "required"
  }

  tags = {
    Name = "halyx-${var.run_id}-runner"
  }
}

# --- MSK Serverless ------------------------------------------------------------------------------------

resource "aws_security_group" "cluster" {
  name        = "halyx-${var.run_id}-msk"
  description = "Halyx real-cloud tests: Kafka with IAM from the runner only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Kafka, SASL/IAM over TLS"
    from_port       = 9098
    to_port         = 9098
    protocol        = "tcp"
    security_groups = [aws_security_group.runner.id]
  }
}

resource "aws_msk_serverless_cluster" "streams" {
  cluster_name = "halyx-${var.run_id}"

  vpc_config {
    subnet_ids         = data.aws_subnets.default.ids
    security_group_ids = [aws_security_group.cluster.id]
  }

  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }
}

# --- claim checks --------------------------------------------------------------------------------------

resource "aws_s3_bucket" "claims" {
  bucket        = "halyx-${var.run_id}-claims"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "claims" {
  bucket                  = aws_s3_bucket.claims.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "runner_ip" {
  value = aws_instance.runner.public_ip
}

output "bootstrap" {
  value = aws_msk_serverless_cluster.streams.bootstrap_brokers_sasl_iam
}

output "cluster_arn" {
  value = aws_msk_serverless_cluster.streams.arn
}

output "claims_bucket" {
  value = aws_s3_bucket.claims.bucket
}
