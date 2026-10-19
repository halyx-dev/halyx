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

# Tier 2 real-cloud test resources: RDS for PostgreSQL and MySQL with IAM database authentication.
# Throwaway: no backups, no final snapshot, reachable only from the test machine's address.

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
  description = "Address the tests connect from, e.g. 203.0.113.7/32"
  type        = string
}

variable "master_password" {
  description = "Generated per run by the script and passed as TF_VAR_master_password"
  type        = string
  sensitive   = true
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
}

resource "aws_db_subnet_group" "db" {
  name       = "halyx-${var.run_id}"
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_security_group" "db" {
  name        = "halyx-${var.run_id}-db"
  description = "Halyx real-cloud tests: database access from the test machine only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "PostgreSQL"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.client_cidr]
  }

  ingress {
    description = "MySQL"
    from_port   = 3306
    to_port     = 3306
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

locals {
  common = {
    instance_class                      = "db.t4g.micro"
    allocated_storage                   = 20
    storage_type                        = "gp3"
    db_name                             = "app"
    username                            = "halyx_admin"
    publicly_accessible                 = true
    iam_database_authentication_enabled = true
    backup_retention_period             = 0
    skip_final_snapshot                 = true
    delete_automated_backups            = true
    deletion_protection                 = false
    apply_immediately                   = true
  }
}

resource "aws_db_instance" "postgres" {
  identifier                          = "halyx-${var.run_id}-pg"
  engine                              = "postgres"
  engine_version                      = "17"
  instance_class                      = local.common.instance_class
  allocated_storage                   = local.common.allocated_storage
  storage_type                        = local.common.storage_type
  db_name                             = local.common.db_name
  username                            = local.common.username
  password                            = var.master_password
  db_subnet_group_name                = aws_db_subnet_group.db.name
  vpc_security_group_ids              = [aws_security_group.db.id]
  publicly_accessible                 = local.common.publicly_accessible
  iam_database_authentication_enabled = local.common.iam_database_authentication_enabled
  backup_retention_period             = local.common.backup_retention_period
  skip_final_snapshot                 = local.common.skip_final_snapshot
  delete_automated_backups            = local.common.delete_automated_backups
  deletion_protection                 = local.common.deletion_protection
  apply_immediately                   = local.common.apply_immediately
}

resource "aws_db_instance" "mysql" {
  identifier                          = "halyx-${var.run_id}-mysql"
  engine                              = "mysql"
  engine_version                      = "8.4"
  instance_class                      = local.common.instance_class
  allocated_storage                   = local.common.allocated_storage
  storage_type                        = local.common.storage_type
  db_name                             = local.common.db_name
  username                            = local.common.username
  password                            = var.master_password
  db_subnet_group_name                = aws_db_subnet_group.db.name
  vpc_security_group_ids              = [aws_security_group.db.id]
  publicly_accessible                 = local.common.publicly_accessible
  iam_database_authentication_enabled = local.common.iam_database_authentication_enabled
  backup_retention_period             = local.common.backup_retention_period
  skip_final_snapshot                 = local.common.skip_final_snapshot
  delete_automated_backups            = local.common.delete_automated_backups
  deletion_protection                 = local.common.deletion_protection
  apply_immediately                   = local.common.apply_immediately
}

output "postgres_host" {
  value = aws_db_instance.postgres.address
}

output "mysql_host" {
  value = aws_db_instance.mysql.address
}

output "master_user" {
  value = local.common.username
}
