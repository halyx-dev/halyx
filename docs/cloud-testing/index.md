---
title: Cloud testing
nav_order: 7
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
| [AWS](aws.md) | 1 | S3, DynamoDB, SSM Parameter Store, Secrets Manager, SNS, SQS | **170/172** passed, 2 skipped (expected) |
| [AWS](aws.md) | 2 | RDS for PostgreSQL and MySQL with IAM auth | **12/12** |
| [AWS](aws.md) | 3 | ElastiCache for Valkey with IAM auth | 52/53: the test IAM policy still needs checking |
| [GCP](gcp.md) | 1 | Cloud Storage, Firestore, Pub/Sub, Secret Manager, Parameter Manager | **176/179** passed, 3 skipped (expected) |
| [GCP](gcp.md) | 2 | Cloud SQL for PostgreSQL and MySQL with IAM auth, direct and through the Cloud SQL connector | **18/18** |
| [GCP](gcp.md) | 3 | Memorystore for Valkey with IAM auth and TLS (private CA) | **54/54** |
| [Azure](azure.md) | 1 | Blob Storage, Cosmos DB, Service Bus, Key Vault, App Configuration (Entra ID only) | **156/158** passed, 2 skipped (expected) |
| [Azure](azure.md) | 2 | Azure Database for PostgreSQL and Azure SQL with Entra ID login | **11/11** |
| [Azure](azure.md) | 3 | Azure Managed Redis with Entra ID (access keys off) | **52/52** |
| [Azure](azure.md) | 4 | Event Hubs through its Kafka endpoint, with Entra ID (shared access keys off) | **24/25** passed, 1 skipped (expected) |

The skipped tests are features a service doesn't offer, such as per-message delays on SNS or Pub/Sub,
and the driver declares them as missing [capabilities](../api/capabilities-and-unwrap.md).

## What is tested

Each cloud is tested in three tiers. The tiers are grouped by how expensive and slow the resources are
to create, and by how they are reached.

| Tier | What | Kits and tests | Reached through |
|---|---|---|---|
| **1** | Serverless services: object storage, document stores, messaging, parameters and secrets | the blob, document store, messaging and parameter store conformance kits, about 160 to 180 tests per cloud | public service endpoints |
| **2** | Managed SQL databases with IAM or Entra ID login | password and token logins, TLS checked on the server side, pooled connections re-logging in with fresh tokens, rejected logins, login latency | a public endpoint restricted to the test machine's address |
| **3** | Managed Redis and Valkey caches with IAM or Entra ID login | the cache conformance kit (49 tests) plus TLS and authentication checks | a bastion and SSH tunnel where the cache is VPC-only (ElastiCache, Memorystore), the public endpoint otherwise (Azure Managed Redis) |
| **4** | Streams on managed Kafka with IAM or Entra ID login | the stream conformance kit (25 tests) | the public Kafka endpoint (Event Hubs); MSK and Managed Service for Apache Kafka are private only and not yet tested |

The conformance kits are the same ones the emulator builds run. A real-cloud run doesn't relax them. Where
a cloud is eventually consistent, the provider's test class declares it explicitly (for example
`listingLag()` and `deleteLag()` for AWS Secrets Manager and Azure Key Vault), and the library itself
never waits on the caller's behalf. Those runs found the bugs listed on each provider's page, under
"What real … found".

## How a run works

One script per cloud runs a tier from start to finish:

```bash
scripts/aws-real-cloud.sh                 # TIER=1 by default; TIER=2 or TIER=3 for the others
scripts/gcp-real-cloud.sh
scripts/azure-real-cloud.sh
IT_TEST='RealAwsIT$SecretsManager' scripts/aws-real-cloud.sh     # a single suite
```

1. **Provision.** Terraform in `infra/<cloud>/tier<n>/` creates the run's resources. Every name includes
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
| Azure | service principal `halyx-test` | `~/.azure/halyx-test.env`, created by `scripts/azure-setup-test-principal.sh` |

The exact policies and roles are on each provider's page. Credentials never enter the repository.

## Running the tests yourself

You need:
- Terraform 1.5 or later, Maven 3.9 or later and Java 21 or later
- the cloud's CLI, signed in
- for the tunnelled tier 3 runs, `ssh`

A tier 1 run takes 10 to 30 minutes, and tiers 2 and 3 take 15 to 30, most of it creating and deleting
managed services. The resources are the smallest tiers available, so a run costs cents.

## Not yet tested on a real cloud

| Cloud | Service | Why |
|---|---|---|
| Azure | Azure Database for MySQL with Entra ID | needs a managed identity that can read the directory, granted by a directory admin |
| AWS | Streams on MSK with IAM | MSK Serverless is reachable only inside its VPC, and Kafka clients connect to each broker by name, so the SSH tunnel used for caches doesn't work; needs a test runner inside the VPC |
| Google Cloud | Streams on Managed Service for Apache Kafka with IAM | private only, as MSK |
