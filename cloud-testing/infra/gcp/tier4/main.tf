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

# Tier 4 real-cloud test resources on GCP: a Managed Service for Apache Kafka cluster (Google IAM login) and a
# small runner VM in a VPC of the run's own, which runs the tests as the test service account. The cluster is
# reachable only inside the VPC, and Kafka clients connect to each broker by its advertised name, so an SSH
# tunnel can't stand in for the runner. SSH to the runner is open only to the test machine's address, with a key
# generated for the run. Plus a bucket for claim checks.

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
  description = "Address the script connects to the runner from, e.g. 203.0.113.7/32"
  type        = string
}

variable "ssh_public_key" {
  description = "Public half of the key pair generated for the run"
  type        = string
}

variable "service_account" {
  description = "The test service account, which the runner runs as"
  type        = string
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
  zone = "${var.region}-a"
}

resource "google_compute_network" "vpc" {
  name                    = "halyx-${var.run_id}"
  auto_create_subnetworks = false
}

resource "google_compute_subnetwork" "subnet" {
  name          = "halyx-${var.run_id}"
  network       = google_compute_network.vpc.id
  region        = var.region
  ip_cidr_range = "10.81.0.0/24"
}

# --- Managed Service for Apache Kafka ------------------------------------------------------------------

# The smallest cluster: 3 vCPUs and 3 GiB. Its brokers get Private Service Connect addresses in the subnet, and
# the service adds DNS names for them to the VPC.
resource "google_managed_kafka_cluster" "streams" {
  cluster_id = "halyx-${var.run_id}"
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

# --- runner --------------------------------------------------------------------------------------------

resource "google_compute_firewall" "ssh" {
  name          = "halyx-${var.run_id}-ssh"
  network       = google_compute_network.vpc.id
  direction     = "INGRESS"
  source_ranges = [var.client_cidr]
  target_tags   = ["halyx-runner"]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

resource "google_compute_instance" "runner" {
  name         = "halyx-${var.run_id}-runner"
  zone         = local.zone
  machine_type = "e2-small" # the test JVM needs more than e2-micro's 1 GiB
  tags         = ["halyx-runner"]

  boot_disk {
    initialize_params {
      image = "ubuntu-os-cloud/ubuntu-2404-lts-amd64"
      size  = 10
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
    access_config {} # an ephemeral public IP for SSH and packages
  }

  # The tests run as the test service account, through the metadata server, as an application on GCE would.
  service_account {
    email  = var.service_account
    scopes = ["cloud-platform"]
  }

  metadata = {
    ssh-keys               = "halyx:${var.ssh_public_key}"
    block-project-ssh-keys = "true"
    enable-oslogin         = "FALSE"
    startup-script         = "#!/bin/bash\napt-get update -q && apt-get install -yq openjdk-21-jre-headless rsync && touch /var/tmp/halyx-ready\n"
  }
}

# --- claim checks --------------------------------------------------------------------------------------

resource "google_storage_bucket" "claims" {
  name                        = "halyx-${var.run_id}-claims"
  location                    = var.region
  force_destroy               = true
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
}

output "runner_ip" {
  value = google_compute_instance.runner.network_interface[0].access_config[0].nat_ip
}

output "bootstrap" {
  value = "bootstrap.${google_managed_kafka_cluster.streams.cluster_id}.${var.region}.managedkafka.${var.project}.cloud.goog:9092"
}

output "cluster_name" {
  value = google_managed_kafka_cluster.streams.cluster_id
}

output "claims_bucket" {
  value = google_storage_bucket.claims.name
}
