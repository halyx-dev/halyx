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

# The portable orders service on GKE: a zonal Standard cluster with Workload Identity Federation for GKE in a VPC of
# the run's own, an Artifact Registry repository for the image, and every service the sample uses. The pods' Kubernetes
# service account acts as the test service account, which already holds the data-plane roles the tier tests use;
# the one-time binding that allows it is in docs/cloud-testing/gcp.md. Cloud SQL is reached through the Cloud SQL
# connector, Memorystore and Managed Service for Apache Kafka through the VPC.

terraform {
  required_version = ">= 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
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

variable "service_account" {
  description = "The test service account: the nodes and the pods run as it"
  type        = string
}

variable "client_cidr" {
  description = "The test machine's address, which sets up the ledger database, e.g. 203.0.113.7/32"
  type        = string
}

variable "admin_password" {
  description = "Generated per run by the script and passed as TF_VAR_admin_password"
  type        = string
  sensitive   = true
}

variable "node_type" {
  type    = string
  default = "e2-standard-2"
}

# Credentials are Application Default Credentials, which impersonate the test service account.
provider "google" {
  project = var.project
  region  = var.region
  default_labels = {
    halyx-test = var.run_id
  }
}

locals {
  name   = "halyx-${var.run_id}"
  zone   = "${var.region}-a"
  prefix = "halyx-${var.run_id}" # the parameter stores' prefix
}

# --- network and cluster ------------------------------------------------------------------------------------------

resource "google_compute_network" "vpc" {
  name                    = local.name
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subnet" {
  name          = local.name
  network       = google_compute_network.vpc.id
  region        = var.region
  ip_cidr_range = "10.82.0.0/20"
  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = "10.84.0.0/16"
  }
  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = "10.85.0.0/20"
  }
}

resource "google_container_cluster" "gke" {
  name                     = local.name
  location                 = local.zone # one zonal cluster's management fee is covered by the free tier
  network                  = google_compute_network.vpc.id
  subnetwork               = google_compute_subnetwork.subnet.id
  remove_default_node_pool = true
  initial_node_count       = 1
  deletion_protection      = false

  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  workload_identity_config {
    workload_pool = "${var.project}.svc.id.goog"
  }

  # The default pool exists only until the cluster is created; it still needs a service account the caller may use.
  node_config {
    service_account = var.service_account
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    disk_size_gb    = 30
    disk_type       = "pd-standard"
  }

  resource_labels = {
    halyx-test = var.run_id
  }
}

resource "google_container_node_pool" "nodes" {
  name       = "nodes"
  location   = local.zone
  cluster    = google_container_cluster.gke.name
  node_count = 1

  node_config {
    machine_type    = var.node_type
    disk_size_gb    = 30
    disk_type       = "pd-standard"
    service_account = var.service_account # pulls images from the run's repository
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    workload_metadata_config {
      mode = "GKE_METADATA"
    }
    resource_labels = {
      halyx-test = var.run_id
    }
  }
}

resource "google_artifact_registry_repository" "images" {
  repository_id = local.name
  location      = var.region
  format        = "DOCKER"
}

# --- receipts, orders, messaging ---------------------------------------------------------------------------------

resource "google_storage_bucket" "receipts" {
  name                        = "${local.name}-receipts"
  location                    = upper(var.region)
  force_destroy               = true
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
}

resource "google_firestore_database" "orders" {
  name                    = local.name
  location_id             = var.region
  type                    = "FIRESTORE_NATIVE"
  deletion_policy         = "DELETE"
  delete_protection_state = "DELETE_PROTECTION_DISABLED"
}

resource "google_pubsub_topic" "placed" {
  name = "${local.name}-placed"
}

resource "google_pubsub_subscription" "fulfilment" {
  name                       = "${local.name}-fulfilment"
  topic                      = google_pubsub_topic.placed.id
  ack_deadline_seconds       = 60
  message_retention_duration = "3600s"
}

# --- order-events and notifications: Eventarc Advanced, bus -> enrollment -> pipeline -> Pub/Sub --------------------
# The pipeline publishes as the test service account; the Eventarc service agent mints its tokens (the one-time grant
# in docs/cloud-testing/gcp.md).

resource "google_eventarc_message_bus" "events" {
  location       = var.region
  message_bus_id = local.name
}

resource "google_pubsub_topic" "notifications" {
  name = "${local.name}-notifications"
}

resource "google_pubsub_subscription" "notifications" {
  name                       = "${local.name}-notifications"
  topic                      = google_pubsub_topic.notifications.id
  ack_deadline_seconds       = 60
  message_retention_duration = "3600s"
}

resource "google_eventarc_pipeline" "events" {
  location    = var.region
  pipeline_id = local.name
  destinations {
    topic = google_pubsub_topic.notifications.id
    authentication_config {
      oauth_token {
        service_account = var.service_account
      }
    }
  }
  logging_config {
    log_severity = "ERROR"
  }
}

resource "google_eventarc_enrollment" "events" {
  location      = var.region
  enrollment_id = local.name
  message_bus   = google_eventarc_message_bus.events.id
  destination   = google_eventarc_pipeline.events.id
  cel_match     = "true" # the run's own bus carries only the service's events
}

# --- settings and secrets: Parameter Manager and Secret Manager ---------------------------------------------------
# Portable names: <prefix>/orders/max-lines is stored as <prefix>__orders__max-lines.

resource "google_parameter_manager_parameter" "settings" {
  for_each     = { "max-lines" = "3", "currency" = "JPY" }
  parameter_id = "${local.prefix}__orders__${each.key}"
  format       = "UNFORMATTED"
}

resource "google_parameter_manager_parameter_version" "settings" {
  for_each             = google_parameter_manager_parameter.settings
  parameter            = each.value.id
  parameter_version_id = "v1"
  parameter_data       = each.key == "max-lines" ? "3" : "JPY"
}

resource "random_password" "signing_key" {
  length  = 32
  special = false
}

resource "google_secret_manager_secret" "signing_key" {
  secret_id = "${local.prefix}__receipt-signing-key"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "signing_key" {
  secret      = google_secret_manager_secret.signing_key.id
  secret_data = random_password.signing_key.result
}

# --- idempotency: Memorystore for Valkey, IAM auth and TLS, through the VPC ----------------------------------------

resource "google_network_connectivity_service_connection_policy" "memorystore" {
  name          = local.name
  location      = var.region
  service_class = "gcp-memorystore"
  network       = google_compute_network.vpc.id
  psc_config {
    subnetworks = [google_compute_subnetwork.subnet.id]
  }
}

resource "google_memorystore_instance" "cache" {
  instance_id                 = local.name
  location                    = var.region
  mode                        = "CLUSTER_DISABLED"
  shard_count                 = 1
  replica_count               = 0
  node_type                   = "SHARED_CORE_NANO"
  engine_version              = "VALKEY_8_0"
  authorization_mode          = "IAM_AUTH"
  transit_encryption_mode     = "SERVER_AUTHENTICATION"
  deletion_protection_enabled = false
  zone_distribution_config {
    mode = "SINGLE_ZONE"
    zone = local.zone
  }
  desired_auto_created_endpoints {
    network    = google_compute_network.vpc.id
    project_id = var.project
  }
  depends_on = [google_network_connectivity_service_connection_policy.memorystore]
}

locals {
  cache_primary = one([
    for c in flatten([for e in google_memorystore_instance.cache.endpoints : [for x in e.connections : x.psc_auto_connection]]) :
    c if c.connection_type == "CONNECTION_TYPE_PRIMARY"
  ])
  cache_ca = join("\n", flatten([
    for ca in google_memorystore_instance.cache.managed_server_ca : [for c in ca.ca_certs : c.certificates]
  ]))
}

# --- order-audit: Managed Service for Apache Kafka, through the VPC --------------------------------------------------

resource "google_managed_kafka_cluster" "streams" {
  cluster_id = local.name
  location   = var.region
  capacity_config {
    vcpu_count   = 3
    memory_bytes = 3221225472
  }
  gcp_config {
    access_config {
      network_configs {
        subnet = google_compute_subnetwork.subnet.id
      }
    }
  }
  rebalance_config {
    mode = "NO_REBALANCE"
  }
}

resource "google_managed_kafka_topic" "audit" {
  topic_id           = "audit"
  cluster            = google_managed_kafka_cluster.streams.cluster_id
  location           = var.region
  partition_count    = 2
  replication_factor = 3
}

# --- ledger: Cloud SQL for PostgreSQL, IAM login through the connector ----------------------------------------------
# The public IP accepts only the test machine, which grants the service account its schema; the connector needs no
# authorized network.

resource "google_sql_database_instance" "ledger" {
  name                = "${local.name}-pg"
  database_version    = "POSTGRES_17"
  region              = var.region
  deletion_protection = false
  settings {
    edition           = "ENTERPRISE"
    tier              = "db-f1-micro"
    availability_type = "ZONAL"
    disk_size         = 10
    disk_autoresize   = false
    user_labels = {
      halyx-test = var.run_id
    }
    database_flags {
      name  = "cloudsql.iam_authentication"
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

# The service keeps connections open and its IAM user owns the ledger table, so neither can be dropped on destroy;
# both go with the instance.
resource "google_sql_database" "orders" {
  name            = "orders"
  instance        = google_sql_database_instance.ledger.name
  deletion_policy = "ABANDON"
}

resource "google_sql_user" "admin" {
  name     = "halyx_admin"
  instance = google_sql_database_instance.ledger.name
  password = var.admin_password
}

resource "google_sql_user" "app" {
  name            = trimsuffix(var.service_account, ".gserviceaccount.com")
  instance        = google_sql_database_instance.ledger.name
  type            = "CLOUD_IAM_SERVICE_ACCOUNT"
  deletion_policy = "ABANDON"
}

# --- outputs -------------------------------------------------------------------------------------------------------

output "cluster" {
  value = google_container_cluster.gke.name
}

output "endpoint" {
  value = google_container_cluster.gke.endpoint
}

output "cluster_ca" {
  value = google_container_cluster.gke.master_auth[0].cluster_ca_certificate
}

output "zone" {
  value = local.zone
}

output "repository" {
  value = "${var.region}-docker.pkg.dev/${var.project}/${google_artifact_registry_repository.images.repository_id}"
}

output "db_public_ip" {
  value = google_sql_database_instance.ledger.public_ip_address
}

output "db_user" {
  value = google_sql_user.app.name
}

output "cache_ca" {
  value = local.cache_ca
}

output "signing_key" {
  value     = random_password.signing_key.result
  sensitive = true
}

output "firestore_database" {
  value = google_firestore_database.orders.name
}

output "db_instance" {
  value = google_sql_database_instance.ledger.connection_name
}

output "env" {
  description = "The service's environment on this run"
  value = {
    GOOGLE_CLOUD_PROJECT              = var.project
    ORDERS_PARAMETER_PREFIX           = local.prefix
    ORDERS_RECEIPTS_BUCKET            = google_storage_bucket.receipts.name
    ORDERS_TABLE                      = "orders"
    ORDERS_TOPIC                      = google_pubsub_topic.placed.name
    ORDERS_FULFILMENT_SUBSCRIPTION    = google_pubsub_subscription.fulfilment.name
    ORDERS_EVENT_BUS                  = google_eventarc_message_bus.events.id
    ORDERS_NOTIFICATIONS_SUBSCRIPTION = google_pubsub_subscription.notifications.name
    ORDERS_KAFKA_BOOTSTRAP_SERVERS    = "bootstrap.${google_managed_kafka_cluster.streams.cluster_id}.${var.region}.managedkafka.${var.project}.cloud.goog:9092"
    ORDERS_AUDIT_TOPIC                = google_managed_kafka_topic.audit.topic_id
    ORDERS_CACHE_ENDPOINT             = "rediss://${local.cache_primary.ip_address}:${local.cache_primary.port}"
    ORDERS_DB_NAME                    = google_sql_database.orders.name
    ORDERS_DB_USER                    = google_sql_user.app.name
  }
}
