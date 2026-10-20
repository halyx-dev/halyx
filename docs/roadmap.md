---
title: Roadmap
nav_order: 11
---

# Roadmap
{: .no_toc }

Halyx has not had its first release yet. This page lists what has been delivered, what is in progress, and what
comes next, in priority order. Work that is planned but waiting for demand, and ideas that were dropped, are at the
end.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Status

| Stage | Work |
|---|---|
| **In progress** | [Release 1.0.0](#release-100) on Maven Central, and [cloud logins](#cloud-logins-assumed-roles-impersonation-and-service-principals) (assumed roles, impersonation and service principals) |
| **Open items** | [Gaps in delivered milestones](#open-items), mostly real-cloud runs and platform limits |
| **Delivered** | [15 milestones](#delivered): every service on AWS, Azure and Google Cloud, Spring Boot 3.5 and 4, reactive and asynchronous APIs, and a portable microservice on EKS, GKE and AKS |

## In progress

### Release 1.0.0

The first release on Maven Central.

| Task | Status |
|---|---|
| Apache-2.0 license, with headers on every source file | Done |
| The `dev.halyx` group id | Done |
| A full build and test pass, on emulators and on real clouds | Done |
| Project metadata: URL, SCM and developers | To do |
| Source and javadoc jars, signing, and the Central publishing plugin | To do |
| JPMS module descriptors | To do |
| Continuous integration | To do |
| API compatibility checks with japicmp | From the release after 1.0.0 |

### Cloud logins: assumed roles, impersonation and service principals

**Goal:** log in as the identity each deployment needs, not only through the default chain: an IAM role in another
AWS account, a Google service account, or an Entra ID application for code running outside Azure. Code on one
cloud can then log in to another through federation rather than keys. See [Provider configuration](providers.md#assumed-roles-and-impersonated-service-accounts).

| Deliverable | Status |
|---|---|
| `Credentials.impersonate(target)`: an AWS IAM role through STS, with an external ID, a session name and a lifetime, from the default chain or an access key | Done |
| The same on Google Cloud: an impersonated service account for every client, for Cloud SQL, Memorystore and Managed Kafka logins, and for the Cloud SQL connector | Done |
| `Credentials.servicePrincipal(...)`: an Entra ID application with a client secret, a certificate or a federated token file, for every Azure service, including database, cache and Event Hubs logins | Done |
| STS, SSO and SSO OIDC in `halyx-aws`, so the default chain's web identity (EKS) and SSO logins work | Done |
| Spring Boot: `credentials.type: impersonate` and `service-principal`, with startup checks | Done |
| Tests: assumed roles on Floci, directly and through Spring Boot; credential mapping on Azure and Google Cloud | Done |
| Real-cloud runs: an assumed role with an external ID on AWS, an impersonated service account on Google Cloud, and a service principal with a secret and with a federated token on Azure | To do, at the end of the phase |

## Open items

Work left over from delivered milestones.

| Item | Milestone | Status |
|---|---|---|
| Real-cloud run on MSK Serverless with IAM, including transactions | Streams | **Blocked:** the test account's AWS Free plan doesn't allow MSK. The script, Terraform and runner are ready ([AWS tier 4](cloud-testing/aws.md#tier-4-not-run)) |
| Real-cloud run on Azure Database for MySQL with Entra ID | SQL databases | **Blocked:** needs a directory-level grant for the server's identity |
| Scoping of the ElastiCache `Connect` permission in the AWS tier 3 test IAM policy | Real-cloud testing | To check |
| The Cloud SQL connector's R2DBC adapters | R2DBC | Planned |
| Kotlin coroutines: `suspend` listeners and coroutine repositories, tested | Reactive | Planned |
| Calls that still block underneath on a virtual thread: Google Cloud Storage (no asynchronous Java API), Service Bus receives (only its async receiver's own stream can settle its messages), and Cosmos DB and Firestore read-then-write calls | Asynchronous I/O | **Platform limit:** revisited when the SDKs allow |

## Delivered

| # | Milestone | What it delivered | Guide |
|---|---|---|---|
| 1 | Core and object storage | The portable API, provider SPI, error model and interceptors; blob storage on S3, Azure Blob Storage and Cloud Storage; conformance kits and emulator tests | [Storage](api/storage.md) |
| 2 | Caches | One Redis and Valkey driver with cloud logins: ElastiCache IAM, Entra ID, Memorystore IAM | [Caches](api/caches.md) |
| 3 | SQL databases | A pooled `DataSource` with cloud IAM login and token refresh: RDS IAM, Entra ID, Cloud SQL, and the Cloud SQL connector | [SQL](api/sql.md) |
| 4 | Parameters and secrets | SSM Parameter Store, Secrets Manager, App Configuration, Key Vault, Parameter Manager, Secret Manager and HashiCorp Vault; Spring configuration import with reload | [Parameters](api/parameters.md) |
| 5 | NoSQL documents | DynamoDB, Cosmos DB and Firestore | [NoSQL](api/nosql.md) |
| 6 | Spring Boot | The starter, properties, auto-configuration, health and Micrometer, on Boot 3.5 and 4 | [Spring Boot](spring-boot.md) |
| 7 | Messaging | SNS and SQS, Service Bus, Pub/Sub; listeners, claim checks, tracing | [Messaging](api/messaging.md) |
| 8 | Production gaps | NoSQL secondary indexes and transactions, batched acks, Service Bus sessions | |
| 9 | Real-cloud testing | The conformance kits on real AWS, Google Cloud and Azure, in tiers, on throwaway infrastructure | [Results](cloud-testing/index.md) |
| 10 | Spring Data repositories | Repositories over DynamoDB, Cosmos DB and Firestore: records as entities, derived queries on keys and indexes, optimistic locking and keyset scrolling; on the emulators and all three clouds | [Repositories](spring-boot.md#spring-data-repositories) |
| 11 | Streams (managed Kafka) | `StreamProducer` and `StreamConsumer` on Amazon MSK (IAM), Event Hubs' Kafka endpoint (Entra ID) and Managed Service for Apache Kafka (Google IAM). Order per partition, consumer groups, listeners with retries and dead letters, manual commits, idempotent and transactional producers, claim checks; Spring for Apache Kafka with Halyx' cloud login; tested on Kafka 3.9 and 4.2.1 brokers. Real clouds: Event Hubs 24/25 (1 skipped as expected), Managed Kafka 25/25 | [Streams](api/streams.md) |
| 12 | Event services | CloudEvents 1.0 and `EventPublisher`, publishing to EventBridge, Event Grid namespace topics and Eventarc Advanced, and receiving through each router's queue, including Event Grid pull subscriptions; on the memory provider, emulators and real clouds, with Spring Boot 3 and 4 | [Events](api/events.md) |
| 13 | R2DBC with cloud IAM login | `halyx-r2dbc`: a pooled R2DBC `ConnectionFactory` for PostgreSQL and MySQL whose new connections log in with RDS IAM, Entra ID or Cloud SQL IAM tokens, or a rotating password; Spring Data R2DBC on Boot 3.5 and 4; real-cloud runs on RDS, Azure Database for PostgreSQL and Cloud SQL. SQL Server stays password-only, because its R2DBC driver takes no access tokens | [R2DBC](spring-boot.md#r2dbc-and-spring-data-r2dbc) |
| 14 | Reactive and asynchronous APIs | `halyx-reactor`: `Mono` and `Flux` for every service with back-pressure and context propagation, reactive repositories, WebFlux and reactive `@Cacheable`, and interoperability with RxJava, Mutiny, `Flow` and `CompletableFuture`. `async()` views of every service, non-blocking where the SDK's async client does it well: S3, Azure Blob Storage, DynamoDB, Cosmos DB, Firestore, SNS, SQS, Service Bus and Pub/Sub publishing, EventBridge, Event Grid, Eventarc, SQS, Pub/Sub and Event Grid receives, Redis and Valkey, Kafka producers, and every parameter backend. The adapters add about 5 µs per call | [Reactive and async](api/reactive.md) |
| 15 | Portable Spring Boot microservices | `halyx-samples/portable-orders`, an orders service on every Halyx API, built once as one image that runs unchanged on every cloud: the cloud is chosen by Spring profile, and optional features cover services a deployment can't offer. One Helm chart deploys the same image digests on EKS (Pod Identity), GKE (Workload Identity Federation) and AKS (Entra Workload ID), with no stored keys. One end-to-end suite runs in memory, on each cloud's emulators, against the image and on the three clusters, on Boot 4 and 3.5: EKS 8/10 (no MSK), GKE and AKS 9/10 | [Portable microservice](portable-service.md) |

## On demand

Planned, and prioritised by demand:

| Feature | What it adds |
|---|---|
| Aurora fast failover | Faster failover through the AWS Advanced JDBC Wrapper |
| Nested field paths | Updates to fields inside nested NoSQL documents |
| Cross-instance cache stampede protection | One load per key across all instances; `getOrLoad` already shares loads within an instance |
| Object storage extras | Archive restore, KMS encryption, versioning, tags and retention |

## Not planned

Considered and dropped, because they already work another way or don't justify the added complexity:

| Idea | Why not |
|---|---|
| Cache endpoint discovery through cloud APIs | You configure the endpoint; discovery adds permissions and failure modes |
| Push notifications for parameter changes | Polling `watch` and configuration reload cover it |
| SSM and Secret Manager labels | Rarely needed; `unwrap` covers them |
| GCS parallel composite uploads and the AWS CRT client | The parallel transfer engines already cover large objects |
| RDS Proxy support | Nothing to add: point `host` at the proxy endpoint, with IAM login if it requires it |
