---
title: Cloud testing
nav_order: 10
has_children: true
permalink: /cloud-testing/
---

# Testing on real clouds
{: .no_toc }

Every Halyx driver passes its conformance kit against emulators on every build (see
[Design, build and test](../design.md#testing)). Emulators are fast and free, but they don't model
everything that matters in production: IAM and Entra ID logins, eventual consistency, asynchronous
deletes, throttling, private certificate authorities, or exactly how a service rejects a request. The
real-cloud runs close that gap by running the **same conformance kits** against real AWS, Google Cloud
and Azure services.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Results at a glance

| Cloud | Tier | Services | Latest result |
|---|---|---|---|
| [AWS](aws.md) | 1 | S3, DynamoDB, SSM Parameter Store, Secrets Manager, SNS, SQS, EventBridge; Spring Data repositories, Spring Boot and halyx-reactor | **227/228** passed, 1 skipped (expected); Spring **16/16** |
| [AWS](aws.md) | 2 | RDS for PostgreSQL and MySQL with IAM auth; R2DBC and Spring Data R2DBC | **16/16**; Spring **2/2** |
| [AWS](aws.md) | 3 | ElastiCache for Valkey with IAM auth; Spring Boot cache beans and `@Cacheable` | **56/56**; Spring **2/2** |
| [GCP](gcp.md) | 1 | Cloud Storage, Firestore, Pub/Sub, Secret Manager, Parameter Manager, Eventarc Advanced; Spring Data repositories, Spring Boot and halyx-reactor | **230/234** passed, 4 skipped (expected); Spring **16/16** |
| [GCP](gcp.md) | 2 | Cloud SQL for PostgreSQL and MySQL with IAM auth, direct and through the Cloud SQL connector; R2DBC and Spring Data R2DBC | **22/22**; Spring **2/2** |
| [GCP](gcp.md) | 3 | Memorystore for Valkey with IAM auth and TLS (private CA); Spring Boot cache beans and `@Cacheable` | **57/57**; Spring **2/2** |
| [GCP](gcp.md) | 4 | Managed Service for Apache Kafka with Google IAM, plus Spring Boot 3 and 4 | **29/29**; Spring **2/2** |
| [Azure](azure.md) | 1 | Blob Storage, Cosmos DB, Service Bus, Key Vault, App Configuration, Event Grid namespaces (Entra ID only); Spring Data repositories, Spring Boot and halyx-reactor | **207/208** passed, 1 skipped (expected); Spring **16/16** |
| [Azure](azure.md) | 2 | Azure Database for PostgreSQL and Azure SQL with Entra ID login; R2DBC and Spring Data R2DBC | **13/13**; Spring **2/2** |
| [Azure](azure.md) | 3 | Azure Managed Redis with Entra ID (access keys off); Spring Boot cache beans and `@Cacheable` | **55/55**; Spring **2/2** |
| [Azure](azure.md) | 4 | Event Hubs through its Kafka endpoint, with Entra ID (shared access keys off), plus Spring Boot 3 and 4 | **27/29** passed, 2 skipped (expected); Spring **2/2** |

The skipped tests are features a service doesn't offer, such as per-message delays on SNS or Pub/Sub,
and the driver declares them as missing [capabilities](../api/capabilities-and-unwrap.md). Azure tier 4's two
skips are the claim-check tests instead: that run has no blob store for the claim check.

## What is tested

Each cloud is tested in up to four tiers. The tiers are grouped by how expensive and slow the resources are
to create, and by how they are reached.

| Tier | What | Kits and tests | Reached through |
|---|---|---|---|
| **1** | Serverless services: object storage, document stores, messaging, parameters and secrets | the blob, document store, document repository, messaging and parameter store conformance kits, about 170 to 190 tests per cloud, then Spring Boot 3 and 4: an auto-configured repository, events, reactive beans, `@HalyxListener` with a claim check, and `spring.config.import` with reload | public service endpoints |
| **2** | Managed SQL databases with IAM or Entra ID login | password and token logins over JDBC and R2DBC, TLS checked on the server side, pooled connections re-logging in with fresh tokens, rejected logins, login latency, then Spring Data R2DBC on Spring Boot 3 and 4 | a public endpoint restricted to the test machine's address |
| **3** | Managed Redis and Valkey caches with IAM or Entra ID login | the cache conformance kit (50 tests) plus TLS and authentication checks, then Spring Boot 3 and 4: `Cache` beans and `@Cacheable` | a bastion and SSH tunnel where the cache is VPC-only (ElastiCache, Memorystore), the public endpoint otherwise (Azure Managed Redis) |
| **4** | Streams on managed Kafka with IAM or Entra ID login | the stream conformance kit (25 tests), then Spring Boot 3 and 4: the starter's stream beans, and Spring for Apache Kafka through Halyx' login | the public Kafka endpoint (Event Hubs); a runner VM inside the VPC where the cluster is private only (Managed Service for Apache Kafka, MSK), since Kafka clients connect to each broker by name and a tunnel can't follow |

The conformance kits are the same ones the emulator builds run. A real-cloud run doesn't relax them. Where
a cloud is eventually consistent, the provider's test class declares it explicitly (for example
`listingLag()` and `deleteLag()` for AWS Secrets Manager and Azure Key Vault), and the library itself
never waits on the caller's behalf. Those runs found the bugs listed on each provider's page, under
"What real … found".

## How a run works

One script per cloud runs a tier from start to finish:

```bash
cloud-testing/scripts/aws-real-cloud.sh                 # TIER=1 by default; TIER=2, 3 or 4 for the others
cloud-testing/scripts/gcp-real-cloud.sh                 # TIER=1 to 4
cloud-testing/scripts/azure-real-cloud.sh               # TIER=1 to 4
IT_TEST='RealAwsIT$SecretsManager' cloud-testing/scripts/aws-real-cloud.sh     # a single suite
```

1. **Provision.** Terraform in `cloud-testing/infra/<cloud>/tier<n>/` creates the run's resources. Every name includes
   a fresh run id (`r<yyMMddHHmm><hex>`), and everything is tagged or labelled `halyx-test=<run id>`.
   Passwords and SSH keys, where a tier needs them, are generated for the run and passed through the
   environment only.
2. **Test.** A `Real…IT` test class (`RealAwsIT`, `RealGcpSqlIT`, `RealAzureCacheIT` and so on) runs the
   kits. It is skipped in normal builds and enabled only by a system property the script sets. Tests
   that need entities Terraform doesn't own, such as topics, queues or secrets, create them with
   run-specific names.
3. **Tear down, always.** On exit, failure or Ctrl-C, the script:
   - sweeps anything the tests left behind
   - runs `terraform destroy`
   - deletes Terraform state that held generated passwords
   - lists every kind of resource the tier creates, and reports `nothing left` or what remains

   A listing that itself fails is reported as `CHECK FAILED`, never taken as "nothing left".

Every run in the published history ended with nothing left behind.

## Identities and permissions

The tests never run as an administrator. Each cloud has a dedicated test identity, holding only what
the tiers need:

| Cloud | Identity | Where its credentials live |
|---|---|---|
| AWS | IAM user `halyx_test` | a named AWS CLI profile |
| GCP | service account `halyx-test`, impersonated | your `gcloud` Application Default Credentials |
| Azure | service principal `halyx-test` | `~/.azure/halyx-test.env`, created by `cloud-testing/scripts/azure-setup-test-principal.sh` |

The exact policies and roles are on each provider's page. Credentials never enter the repository.

## Running the tests yourself

You need:
- Terraform 1.5 or later, Maven 3.9 or later and Java 21 or later
- the cloud's CLI, signed in
- for the tunnelled tier 3 runs and the tier 4 runners, `ssh` and `scp`

A tier 1 run takes 10 to 30 minutes, and tiers 2 and 3 take 15 to 30, most of it creating and deleting
managed services. Tier 4 takes about 10 minutes on Azure, and about 45 on Google Cloud, where creating the
Kafka cluster takes 20 to 25. The resources are the smallest tiers available, so a run costs cents.

## Portable microservice

The [portable orders service](../portable-service.md) runs one end-to-end suite (`PortableOrdersSuite`, 10 tests)
against every deployment, on Spring Boot 4 and 3.5. A feature a deployment doesn't offer is skipped; the last test
checks that a feature that's off has no endpoint, so it is skipped wherever every feature is on.

| Deployment | events | streams | cache | sql | Boot 4 | Boot 3.5 |
|---|---|---|---|---|---|---|
| In memory (`local`) | yes | yes | yes | no stub | **9/10**, 1 skipped (sql) | **9/10**, 1 skipped |
| AWS emulators: Floci, PostgreSQL, Valkey, Kafka | EventBridge | Kafka | Valkey | PostgreSQL | **9/10**, 1 skipped (all on) | **9/10**, 1 skipped |
| Azure emulators: Floci-az, Service Bus emulator, PostgreSQL, Valkey, Kafka | no emulator | Kafka | Valkey | PostgreSQL | **8/10**, 2 skipped (events, all on) | **8/10**, 2 skipped |
| Google Cloud emulators: Floci-gcp, PostgreSQL, Valkey, Kafka | no emulator | Kafka | Valkey | PostgreSQL | **8/10**, 2 skipped (events, all on) | **8/10**, 2 skipped |
| The image on the AWS emulators (`-Pimage`) | EventBridge | Kafka | Valkey | PostgreSQL | **9/10**, 1 skipped (all on) | |
| The image on **EKS** (Pod Identity) | EventBridge | off: no MSK | ElastiCache | RDS | **8/10**, 2 skipped (streams, all on) | **8/10**, 2 skipped |
| The image on **GKE** (Workload Identity Federation) | Eventarc Advanced | Managed Kafka | Memorystore | Cloud SQL, connector | **9/10**, 1 skipped (all on) | **9/10**, 1 skipped |
| The image on **AKS** (Entra Workload ID) | Event Grid | Event Hubs | Managed Redis | Azure Database for PostgreSQL | **9/10**, 1 skipped (all on) | **9/10**, 1 skipped |

### One image on three clusters

`cloud-testing/scripts/build-portable-orders-images.sh` builds the two images once. `eks-real-cloud.sh`,
`gke-real-cloud.sh` and `aks-real-cloud.sh` push them and deploy them with one Helm chart. Each cluster is throwaway,
logs in with its platform's workload identity, and uses only its own cloud's services. Each run records the digest
it pushed and the digest its pods ran, and fails if they differ. In the latest runs, all three registries got the same
digests, and every pod ran them:

| Image | Digest | EKS (ECR) | GKE (Artifact Registry) | AKS (ACR) |
|---|---|---|---|---|
| `portable-orders` (Spring Boot 4) | `sha256:b905c4c5…1a6a` | ran it | ran it | ran it |
| `portable-orders-boot3` (Spring Boot 3.5) | `sha256:7b5b4e59…5a9b` | ran it | ran it | ran it |

| Run | Cluster | Boot 4 | Boot 3.5 | Teardown |
|---|---|---|---|---|
| `r26102013300aab` | EKS, `ap-southeast-2`, one `m7i-flex.large` node | 8/10, 2 skipped | 8/10, 2 skipped | nothing left |
| `r26102013369237` | GKE, `australia-southeast1-a`, one `e2-standard-2` node | 9/10, 1 skipped | 9/10, 1 skipped | nothing left |
| `r26102013275a4d` | AKS, `australiaeast`, one `Standard_B2s_v2` node | 9/10, 1 skipped | 9/10, 1 skipped | nothing left |

Each run takes 25 to 45 minutes, most of it creating the managed services; GKE's Managed Kafka cluster alone takes
about 15 minutes.

What the runs found:
- **Numbers lose their scale.** DynamoDB, Cosmos DB and Firestore keep a decimal's value but not its scale, so a
  price of `3.50` reads back as `3.5`. The service formats amounts itself; the in-memory store hid this.
- **A `halyx.*` key is a bean name.** Classes called `Fulfilment`, `Receipts` and `Ledger` clashed with the
  `fulfilment` subscription, the `receipts` blob store and the `ledger` database. Startup failed, naming both.
- **Parameter Manager has no emulator.** The Google Cloud emulator run reads its settings from Secret Manager
  instead; the real-cloud run reads them from Parameter Manager.
- **A least-privilege policy must cover the health check.** The secrets store's health indicator reads a probe secret,
  `<prefix>/halyx-ping`, and expects "not found". On EKS the pod's role allowed only the signing key, so the probe got
  "access denied" and health was DOWN. The role now allows reads under the run's prefix.
- **Buildpack images cap direct memory at 10 MB.** On AKS the Azure SDK's Netty client ran out
  (`OutOfDirectMemoryError`) and a request hung. The chart sets `-XX:MaxDirectMemorySize=64M` in
  `JAVA_TOOL_OPTIONS`, and the memory calculator sizes the heap around it.
- **A database user that owns objects can't be dropped.** Cloud SQL refused to delete the IAM user, which owns the
  ledger table, so the first GKE teardown left the instance behind (deleted by hand). Terraform now abandons the user
  and the database, which go with the instance, and the scripts uninstall the release before destroying.
- **AKS details.** `kube_admin_config` is empty on clusters without Entra ID RBAC (`kube_config` holds the
  credentials), and Service Bus and Event Hubs namespaces share one DNS zone, so their names must differ. The
  subscription offers `Standard_B2s_v2` for AKS but not `Standard_B2s`.

## Not yet tested on a real cloud

| Cloud | Service | Why |
|---|---|---|
| Azure | Azure Database for MySQL with Entra ID | needs a managed identity that can read the directory, granted by a directory admin |
| AWS | Streams on MSK Serverless with IAM | the test account's Free plan doesn't allow MSK; tier 4 is [ready](aws.md#tier-4-not-run) |
