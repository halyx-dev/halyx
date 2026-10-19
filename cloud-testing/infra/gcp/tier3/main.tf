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

# Tier 3 real-cloud test resources on GCP: Memorystore for Valkey with IAM authentication and TLS, reached
# through an SSH tunnel to a small bastion (Memorystore has no public endpoints). Everything lives in a VPC
# of the run's own. Throwaway: no persistence or backups, and SSH is open only to the test machine's
# address with a key generated for the run.

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
  description = "Address the tests connect from, e.g. 203.0.113.7/32"
  type        = string
}

variable "ssh_public_key" {
  description = "Public half of the key pair generated for the run"
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
  ip_cidr_range = "10.80.0.0/24"
}

# Memorystore creates its Private Service Connect endpoints in the VPC under this policy.
resource "google_network_connectivity_service_connection_policy" "memorystore" {
  name          = "halyx-${var.run_id}"
  location      = var.region
  service_class = "gcp-memorystore"
  network       = google_compute_network.vpc.id
  psc_config {
    subnetworks = [google_compute_subnetwork.subnet.id]
  }
}

resource "google_memorystore_instance" "cache" {
  instance_id                 = "halyx-${var.run_id}"
  location                    = var.region
  mode                        = "CLUSTER_DISABLED" # one endpoint, no redirects to node IPs the tunnel can't reach
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

# --- bastion -------------------------------------------------------------------------------------------

resource "google_compute_firewall" "ssh" {
  name          = "halyx-${var.run_id}-ssh"
  network       = google_compute_network.vpc.id
  direction     = "INGRESS"
  source_ranges = [var.client_cidr]
  target_tags   = ["halyx-bastion"]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

resource "google_compute_instance" "bastion" {
  name         = "halyx-${var.run_id}-bastion"
  zone         = local.zone
  machine_type = "e2-micro"
  tags         = ["halyx-bastion"]

  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-12"
      size  = 10
    }
  }

  network_interface {
    subnetwork = google_compute_subnetwork.subnet.id
    access_config {} # an ephemeral public IP for SSH
  }

  # No service account: the bastion only forwards a port.
  metadata = {
    ssh-keys               = "halyx:${var.ssh_public_key}"
    block-project-ssh-keys = "true"
    enable-oslogin         = "FALSE"
  }
}

output "bastion_ip" {
  value = google_compute_instance.bastion.network_interface[0].access_config[0].nat_ip
}

locals {
  primary = one([
    for c in flatten([for e in google_memorystore_instance.cache.endpoints : [for x in e.connections : x.psc_auto_connection]]) :
    c if c.connection_type == "CONNECTION_TYPE_PRIMARY"
  ])
}

output "cache_ip" {
  value = local.primary.ip_address
}

output "cache_port" {
  value = local.primary.port
}

output "cache_name" {
  value = google_memorystore_instance.cache.instance_id
}
