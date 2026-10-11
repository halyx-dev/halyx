# Tier 3 real-cloud test resources: ElastiCache for Valkey with TLS and IAM users, reached through an SSH
# tunnel to a small bastion (ElastiCache has no public endpoints). Throwaway: no snapshots, and SSH is open
# only to the test machine's address with a key pair generated for the run.

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

variable "default_user_password" {
  description = "Generated per run by the script and passed as TF_VAR_default_user_password; the user is off"
  type        = string
  sensitive   = true
}

variable "ssh_public_key" {
  description = "Public half of the key pair generated for the run"
  type        = string
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      cirrus-test = var.run_id
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

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

# --- bastion -------------------------------------------------------------------------------------------

resource "aws_security_group" "bastion" {
  name        = "cirrus-${var.run_id}-bastion"
  description = "Cirrus real-cloud tests: SSH from the test machine only"
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

resource "aws_key_pair" "bastion" {
  key_name   = "cirrus-${var.run_id}"
  public_key = var.ssh_public_key
}

resource "aws_instance" "bastion" {
  ami                         = data.aws_ssm_parameter.al2023_arm64.value
  instance_type               = "t4g.micro" # free-tier eligible: accounts on the Free Tier plan can't launch t4g.nano
  subnet_id                   = data.aws_subnets.default.ids[0]
  vpc_security_group_ids      = [aws_security_group.bastion.id]
  key_name                    = aws_key_pair.bastion.key_name
  associate_public_ip_address = true

  metadata_options {
    http_tokens = "required"
  }

  tags = {
    Name = "cirrus-${var.run_id}-bastion"
  }
}

# --- cache ---------------------------------------------------------------------------------------------

resource "aws_security_group" "cache" {
  name        = "cirrus-${var.run_id}-cache"
  description = "Cirrus real-cloud tests: Valkey from the bastion only"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description     = "Valkey"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.bastion.id]
  }
}

resource "aws_elasticache_subnet_group" "cache" {
  name       = "cirrus-${var.run_id}"
  subnet_ids = data.aws_subnets.default.ids
}

# Every user group must contain a user named "default"; this one is switched off. Valkey users can't be
# password-free, so it gets a random password that nothing uses.
resource "aws_elasticache_user" "default" {
  user_id       = "cirrus-${var.run_id}-default"
  user_name     = "default"
  engine        = "valkey"
  access_string = "off -@all"
  authentication_mode {
    type      = "password"
    passwords = [var.default_user_password]
  }
}

# IAM users: user_id must equal user_name. The test identity may connect as "app" but not as "denied".
resource "aws_elasticache_user" "app" {
  user_id       = "cirrus-${var.run_id}-app"
  user_name     = "cirrus-${var.run_id}-app"
  engine        = "valkey"
  access_string = "on ~* +@all"
  authentication_mode {
    type = "iam"
  }
}

resource "aws_elasticache_user" "denied" {
  user_id       = "cirrus-${var.run_id}-denied"
  user_name     = "cirrus-${var.run_id}-denied"
  engine        = "valkey"
  access_string = "on ~* +@all"
  authentication_mode {
    type = "iam"
  }
}

resource "aws_elasticache_user_group" "cache" {
  user_group_id = "cirrus-${var.run_id}"
  engine        = "valkey"
  user_ids = [
    aws_elasticache_user.default.user_id,
    aws_elasticache_user.app.user_id,
    aws_elasticache_user.denied.user_id,
  ]
}

resource "aws_elasticache_replication_group" "cache" {
  replication_group_id       = "cirrus-${var.run_id}"
  description                = "Cirrus real-cloud tests"
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

output "bastion_ip" {
  value = aws_instance.bastion.public_ip
}

output "cache_host" {
  value = aws_elasticache_replication_group.cache.primary_endpoint_address
}

output "cache_name" {
  value = aws_elasticache_replication_group.cache.replication_group_id
}

output "app_user" {
  value = aws_elasticache_user.app.user_id
}

output "denied_user" {
  value = aws_elasticache_user.denied.user_id
}
