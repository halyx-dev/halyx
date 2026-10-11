# Tier 2 real-cloud test resources on GCP: Cloud SQL for PostgreSQL and MySQL with IAM database
# authentication. Throwaway: shared-core, no backups, no deletion protection, and the public IP accepts
# only the test machine's address (the Cloud SQL connector needs no authorized network at all).

terraform {
  required_version = ">= 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
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

variable "run_id" {
  type = string
}

variable "client_cidr" {
  description = "Address the direct-connection tests connect from, e.g. 203.0.113.7/32"
  type        = string
}

variable "iam_service_account" {
  description = "The service account the tests run as; it becomes an IAM database user"
  type        = string
}

variable "admin_password" {
  description = "Generated per run by the script and passed as TF_VAR_admin_password"
  type        = string
  sensitive   = true
}

variable "password_only_password" {
  description = "Generated per run; for the user that is not enabled for IAM"
  type        = string
  sensitive   = true
}

# Credentials are Application Default Credentials, which impersonate the test service account.
provider "google" {
  project = var.project
  region  = var.region
  default_labels = {
    cirrus-test = var.run_id
  }
}

locals {
  engines = {
    pg = {
      version  = "POSTGRES_17"
      iam_flag = "cloudsql.iam_authentication"
      # PostgreSQL IAM users are the service account email without ".gserviceaccount.com".
      iam_user = trimsuffix(var.iam_service_account, ".gserviceaccount.com")
    }
    mysql = {
      version  = "MYSQL_8_4"
      iam_flag = "cloudsql_iam_authentication"
      # MySQL IAM users are the part of the email before "@" (Cloud SQL maps the full email to it).
      iam_user = var.iam_service_account
    }
  }
}

resource "google_sql_database_instance" "db" {
  for_each            = local.engines
  name                = "cirrus-${var.run_id}-${each.key}"
  database_version    = each.value.version
  region              = var.region
  deletion_protection = false

  settings {
    edition           = "ENTERPRISE" # shared-core tiers aren't available on Enterprise Plus
    tier              = "db-f1-micro"
    availability_type = "ZONAL"
    disk_size         = 10
    disk_autoresize   = false
    user_labels = {
      cirrus-test = var.run_id
    }

    database_flags {
      name  = each.value.iam_flag
      value = "on"
    }

    backup_configuration {
      enabled = false
    }

    ip_configuration {
      ipv4_enabled = true
      ssl_mode     = "ENCRYPTED_ONLY"
      authorized_networks {
        name  = "test-machine"
        value = var.client_cidr
      }
    }
  }
}

resource "google_sql_database" "app" {
  for_each = local.engines
  name     = "app"
  instance = google_sql_database_instance.db[each.key].name
}

resource "google_sql_user" "admin" {
  for_each = local.engines
  name     = "cirrus_admin"
  instance = google_sql_database_instance.db[each.key].name
  password = var.admin_password
}

resource "google_sql_user" "iam" {
  for_each = local.engines
  name     = each.value.iam_user
  instance = google_sql_database_instance.db[each.key].name
  type     = "CLOUD_IAM_SERVICE_ACCOUNT"
}

resource "google_sql_user" "password_only" {
  for_each = local.engines
  name     = "cirrus_password_only"
  instance = google_sql_database_instance.db[each.key].name
  password = var.password_only_password
}

output "postgres_host" {
  value = google_sql_database_instance.db["pg"].public_ip_address
}

output "mysql_host" {
  value = google_sql_database_instance.db["mysql"].public_ip_address
}

output "postgres_instance" {
  value = google_sql_database_instance.db["pg"].connection_name
}

output "mysql_instance" {
  value = google_sql_database_instance.db["mysql"].connection_name
}

output "admin_user" {
  value = "cirrus_admin"
}
