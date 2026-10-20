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

# The portable orders service on EKS: a cluster with EKS Pod Identity in the default VPC, an ECR repository for the
# image, and the services the sample uses. The pods log in as an IAM role associated with their Kubernetes service
# account; it may use only the run's resources. Streams are off: MSK isn't available on the test account's plan.
# RDS is public only to the test machine, which creates the IAM database user; the pods reach it, and ElastiCache,
# inside the VPC.

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.80"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
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
  description = "The test machine's address, which sets up the ledger database, e.g. 203.0.113.7/32"
  type        = string
}

variable "master_password" {
  description = "Generated per run by the script and passed as TF_VAR_master_password"
  type        = string
  sensitive   = true
}

variable "cache_default_password" {
  description = "Generated per run; the cache's disabled default user still needs one"
  type        = string
  sensitive   = true
}

variable "node_type" {
  description = "Free-tier eligible, for accounts on the AWS Free plan"
  type        = string
  default     = "m7i-flex.large"
}

variable "namespace" {
  type    = string
  default = "portable-orders"
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      halyx-test = var.run_id
    }
  }
}

data "aws_caller_identity" "current" {}

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

locals {
  name    = "halyx-${var.run_id}"
  prefix  = "halyx-${var.run_id}" # the parameter stores' prefix
  db_user = "portable_orders"
}

# --- cluster ---------------------------------------------------------------------------------------------------------

resource "aws_iam_role" "cluster" {
  name = "${local.name}-cluster"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "eks.amazonaws.com" }, Action = ["sts:AssumeRole", "sts:TagSession"] }]
  })
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_eks_cluster" "eks" {
  name     = local.name
  role_arn = aws_iam_role.cluster.arn

  vpc_config {
    subnet_ids              = data.aws_subnets.default.ids
    endpoint_public_access  = true
    endpoint_private_access = true
  }

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = true # the test identity administers the cluster
  }

  depends_on = [aws_iam_role_policy_attachment.cluster]
}

resource "aws_iam_role" "node" {
  name = "${local.name}-node"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
  ])
  role       = aws_iam_role.node.name
  policy_arn = each.value
}

resource "aws_eks_node_group" "nodes" {
  cluster_name    = aws_eks_cluster.eks.name
  node_group_name = "nodes"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = data.aws_subnets.default.ids
  instance_types  = [var.node_type]
  ami_type        = "AL2023_x86_64_STANDARD"
  disk_size       = 20

  scaling_config {
    desired_size = 1
    min_size     = 1
    max_size     = 1
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

resource "aws_eks_addon" "pod_identity" {
  cluster_name = aws_eks_cluster.eks.name
  addon_name   = "eks-pod-identity-agent"
}

resource "aws_ecr_repository" "images" {
  name         = "${local.name}/portable-orders"
  force_delete = true
}

# --- the workload's role ---------------------------------------------------------------------------------------------

resource "aws_iam_role" "app" {
  name = "${local.name}-app"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "pods.eks.amazonaws.com" }, Action = ["sts:AssumeRole", "sts:TagSession"] }]
  })
}

resource "aws_eks_pod_identity_association" "app" {
  cluster_name    = aws_eks_cluster.eks.name
  namespace       = var.namespace
  service_account = "portable-orders"
  role_arn        = aws_iam_role.app.arn
  depends_on      = [aws_eks_addon.pod_identity]
}

resource "aws_iam_role_policy" "app" {
  name = "portable-orders"
  role = aws_iam_role.app.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = "${aws_s3_bucket.receipts.arn}/*" },
      { Effect = "Allow", Action = ["s3:ListBucket"], Resource = aws_s3_bucket.receipts.arn },
      {
        Effect = "Allow"
        Action = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:DeleteItem", "dynamodb:Query",
        "dynamodb:BatchGetItem", "dynamodb:BatchWriteItem", "dynamodb:DescribeTable", "dynamodb:ConditionCheckItem"]
        Resource = [aws_dynamodb_table.orders.arn, "${aws_dynamodb_table.orders.arn}/index/*"]
      },
      { Effect = "Allow", Action = ["sns:Publish"], Resource = aws_sns_topic.placed.arn },
      {
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility", "sqs:GetQueueUrl", "sqs:GetQueueAttributes"]
        Resource = [aws_sqs_queue.fulfilment.arn, aws_sqs_queue.notifications.arn]
      },
      { Effect = "Allow", Action = ["events:PutEvents"], Resource = aws_cloudwatch_event_bus.events.arn },
      {
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        Resource = ["arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${local.prefix}/*",
        "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/${local.prefix}"]
      },
      # The whole prefix, not only the signing key: the health check reads a probe secret, <prefix>/halyx-ping, and
      # needs "not found" rather than "access denied".
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = "arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:${local.prefix}/*"
      },
      { Effect = "Allow", Action = ["secretsmanager:ListSecrets", "secretsmanager:BatchGetSecretValue"], Resource = "*" },
      {
        Effect   = "Allow"
        Action   = ["rds-db:connect"]
        Resource = "arn:aws:rds-db:${var.region}:${data.aws_caller_identity.current.account_id}:dbuser:${aws_db_instance.ledger.resource_id}/${local.db_user}"
      },
      {
        Effect   = "Allow"
        Action   = ["elasticache:Connect"]
        Resource = [aws_elasticache_replication_group.cache.arn, aws_elasticache_user.app.arn]
      },
    ]
  })
}

# --- receipts and orders ---------------------------------------------------------------------------------------------

resource "aws_s3_bucket" "receipts" {
  bucket        = "${local.name}-receipts"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "receipts" {
  bucket                  = aws_s3_bucket.receipts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_dynamodb_table" "orders" {
  name         = "${local.name}-orders"
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
}

# --- order-placed and fulfilment: SNS delivering raw messages to SQS ------------------------------------------------

resource "aws_sns_topic" "placed" {
  name = "${local.name}-placed"
}

resource "aws_sqs_queue" "fulfilment" {
  name                       = "${local.name}-fulfilment"
  visibility_timeout_seconds = 30
  message_retention_seconds  = 3600
}

resource "aws_sqs_queue_policy" "fulfilment" {
  queue_url = aws_sqs_queue.fulfilment.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "sns.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.fulfilment.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_sns_topic.placed.arn } }
    }]
  })
}

resource "aws_sns_topic_subscription" "fulfilment" {
  topic_arn            = aws_sns_topic.placed.arn
  protocol             = "sqs"
  endpoint             = aws_sqs_queue.fulfilment.arn
  raw_message_delivery = true
  depends_on           = [aws_sqs_queue_policy.fulfilment]
}

# --- order-events and notifications: EventBridge routing the service's events to SQS --------------------------------

resource "aws_cloudwatch_event_bus" "events" {
  name = local.name
}

resource "aws_sqs_queue" "notifications" {
  name                       = "${local.name}-notifications"
  visibility_timeout_seconds = 30
  message_retention_seconds  = 3600
}

resource "aws_cloudwatch_event_rule" "placed" {
  name           = "${local.name}-placed"
  event_bus_name = aws_cloudwatch_event_bus.events.name
  event_pattern  = jsonencode({ source = ["/portable-orders"] })
}

resource "aws_cloudwatch_event_target" "notifications" {
  rule           = aws_cloudwatch_event_rule.placed.name
  event_bus_name = aws_cloudwatch_event_bus.events.name
  target_id      = "notifications"
  arn            = aws_sqs_queue.notifications.arn
}

resource "aws_sqs_queue_policy" "notifications" {
  queue_url = aws_sqs_queue.notifications.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "events.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.notifications.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_cloudwatch_event_rule.placed.arn } }
    }]
  })
}

# --- settings and secrets: SSM Parameter Store and Secrets Manager ---------------------------------------------------
# Portable names: <prefix>/orders/max-lines is the parameter /<prefix>/orders/max-lines.

resource "aws_ssm_parameter" "settings" {
  for_each = { "max-lines" = "8", "currency" = "CAD" }
  name     = "/${local.prefix}/orders/${each.key}"
  type     = "String"
  value    = each.value
}

resource "random_password" "signing_key" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "signing_key" {
  name                    = "${local.prefix}/receipt-signing-key"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "signing_key" {
  secret_id     = aws_secretsmanager_secret.signing_key.id
  secret_string = random_password.signing_key.result
}

# --- idempotency: ElastiCache for Valkey with an IAM user, inside the VPC ---------------------------------------------

resource "aws_security_group" "cache" {
  name        = "${local.name}-cache"
  description = "Halyx portable orders: Valkey from the cluster only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Valkey"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_eks_cluster.eks.vpc_config[0].cluster_security_group_id]
  }
}

resource "aws_elasticache_subnet_group" "cache" {
  name       = local.name
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_elasticache_user" "default" {
  user_id       = "${local.name}-default"
  user_name     = "default"
  engine        = "valkey"
  access_string = "off -@all"
  authentication_mode {
    type      = "password"
    passwords = [var.cache_default_password]
  }
}

resource "aws_elasticache_user" "app" {
  user_id       = "${local.name}-app"
  user_name     = "${local.name}-app"
  engine        = "valkey"
  access_string = "on ~* +@all"
  authentication_mode {
    type = "iam"
  }
}

resource "aws_elasticache_user_group" "cache" {
  user_group_id = local.name
  engine        = "valkey"
  user_ids      = [aws_elasticache_user.default.user_id, aws_elasticache_user.app.user_id]
}

resource "aws_elasticache_replication_group" "cache" {
  replication_group_id       = local.name
  description                = "Halyx portable orders"
  engine                     = "valkey"
  engine_version             = "8.0"
  node_type                  = "cache.t4g.micro"
  num_cache_clusters         = 1
  port                       = 6379
  subnet_group_name          = aws_elasticache_subnet_group.cache.name
  security_group_ids         = [aws_security_group.cache.id]
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true
  user_group_ids             = [aws_elasticache_user_group.cache.user_group_id]
  automatic_failover_enabled = false
  snapshot_retention_limit   = 0
  apply_immediately          = true
}

# --- ledger: RDS for PostgreSQL with IAM auth ---------------------------------------------------------------------------

resource "aws_security_group" "db" {
  name        = "${local.name}-db"
  description = "Halyx portable orders: PostgreSQL from the cluster and the test machine"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "PostgreSQL from the cluster"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_eks_cluster.eks.vpc_config[0].cluster_security_group_id]
  }

  ingress {
    description = "PostgreSQL from the test machine, which creates the IAM user"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.client_cidr]
  }
}

resource "aws_db_subnet_group" "db" {
  name       = local.name
  subnet_ids = data.aws_subnets.default.ids
}

resource "aws_db_instance" "ledger" {
  identifier                          = "${local.name}-pg"
  engine                              = "postgres"
  engine_version                      = "17"
  instance_class                      = "db.t4g.micro"
  allocated_storage                   = 20
  storage_type                        = "gp3"
  db_name                             = "orders"
  username                            = "halyx_admin"
  password                            = var.master_password
  db_subnet_group_name                = aws_db_subnet_group.db.name
  vpc_security_group_ids              = [aws_security_group.db.id]
  publicly_accessible                 = true
  iam_database_authentication_enabled = true
  backup_retention_period             = 0
  skip_final_snapshot                 = true
  delete_automated_backups            = true
  deletion_protection                 = false
  apply_immediately                   = true
}

# --- outputs -----------------------------------------------------------------------------------------------------------

output "cluster" {
  value = aws_eks_cluster.eks.name
}

output "repository" {
  value = aws_ecr_repository.images.repository_url
}

output "db_host" {
  value = aws_db_instance.ledger.address
}

output "db_user" {
  value = local.db_user
}

output "signing_key" {
  value     = random_password.signing_key.result
  sensitive = true
}

output "env" {
  description = "The service's environment on this run"
  value = {
    AWS_REGION                        = var.region
    ORDERS_PARAMETER_PREFIX           = local.prefix
    ORDERS_RECEIPTS_BUCKET            = aws_s3_bucket.receipts.id
    ORDERS_TABLE                      = aws_dynamodb_table.orders.name
    ORDERS_TOPIC                      = aws_sns_topic.placed.arn # an ARN needs no sns:ListTopics to resolve
    ORDERS_FULFILMENT_SUBSCRIPTION    = aws_sqs_queue.fulfilment.name
    ORDERS_EVENT_BUS                  = aws_cloudwatch_event_bus.events.name
    ORDERS_NOTIFICATIONS_SUBSCRIPTION = aws_sqs_queue.notifications.name
    ORDERS_CACHE_ENDPOINT             = "rediss://${aws_elasticache_replication_group.cache.primary_endpoint_address}:6379"
    ORDERS_CACHE_USER                 = aws_elasticache_user.app.user_id
    ORDERS_CACHE_NAME                 = aws_elasticache_replication_group.cache.replication_group_id
    ORDERS_DB_HOST                    = aws_db_instance.ledger.address
    ORDERS_DB_USER                    = local.db_user
  }
}
