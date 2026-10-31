---
title: Roadmap
nav_order: 11
---

# Roadmap
{: .no_toc }

Halyx 1.0.2 is on Maven Central. This page lists what has been delivered, what is left over, and what
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
| **Released** | [1.0.2](#release-102), a patch release, on 25 October 2026; [1.0.1](#release-101), a patch release, and [1.0.0](#release-100), the first release, both on 23 October 2026; all on Maven Central |
| **Coming next** | [Planned additions](#coming-next): R2DBC through the Cloud SQL connector, and more real-cloud test runs |
| **Delivered** | [18 milestones](#delivered): every service on AWS, Azure and Google Cloud, Spring Boot 3.5 and 4, reactive and asynchronous APIs, Kotlin coroutines, function invocation, a portable microservice on EKS, GKE and AKS, and cloud logins across clouds |

## Released

### Release 1.0.2

A patch release, on 25 October 2026. See the [release notes](https://github.com/halyx-dev/halyx/blob/main/CHANGELOG.md).

| Fix | Who it affects |
|---|---|
| The JSON cache codec prefers Jackson 3 whenever it's present | Spring Boot 4 applications with `halyx.cache-manager.codec=json` and Jackson 2 on the classpath too, as `halyx-azure` brings it, where caching a value with an `Instant` failed |
| A started `ReactiveHalyx` call runs to completion when it's cancelled, and its result is dropped, instead of having its thread interrupted | Code that cancels reactive calls, by `timeout()` for example, on the default virtual-thread scheduler or `boundedElastic` |

It also adds a [plain Java quickstart](https://github.com/halyx-dev/halyx/tree/main/halyx-samples/plain-java-quickstart) sample, without Spring, contributed in [#8](https://github.com/halyx-dev/halyx/pull/8).

### Release 1.0.1

A patch release, on 23 October 2026. See the [release notes](https://github.com/halyx-dev/halyx/blob/main/CHANGELOG.md).

| Fix | Who it affects |
|---|---|
| `halyx-kafka` excludes the unpatched `org.lz4:lz4-java` that `kafka-clients` brings, leaving only the patched `at.yawk.lz4` one | Gradle builds on Spring Boot 3.5 with `halyx-aws`, `halyx-azure` or `halyx-gcp`, which failed to resolve |
| `ReactiveBlobStore.getBody` closes the blob before the completion or error reaches the subscriber | Code that checks the blob is closed as soon as the `Flux` completes |

### Release 1.0.0

The first release on Maven Central, on 23 October 2026.

| Task | Status |
|---|---|
| Apache-2.0 license, with headers on every source file | Done |
| The `dev.halyx` group id | Done |
| A full build and test pass, on emulators and on real clouds | Done |
| Project metadata: URL, SCM and developers | Done |
| Source and javadoc jars, signing, and the Central publishing plugin (`mvn -Prelease deploy`) | Done |
| Java module names: an `Automatic-Module-Name` in each published jar, such as `dev.halyx` for `halyx-core` and `dev.halyx.aws` for `halyx-aws`, checked together on the module path | Done |
| Continuous integration: unit and emulator tests on every push, and the documentation site | Done |
| A security policy ([`SECURITY.md`](https://github.com/halyx-dev/halyx/blob/main/SECURITY.md)) and [release notes](https://github.com/halyx-dev/halyx/blob/main/CHANGELOG.md) | Done |
| A public repository, and the documentation at halyx.dev | Done |
| The release: version 1.0.0, a `v1.0.0` tag, the upload to Maven Central, and a GitHub release | Done |
| `module-info.java` descriptors | Later: most of the cloud SDKs ship without them |
| API compatibility checks with japicmp | From the release after 1.0.0 |

## Coming next

Additions planned on top of 1.0.0.

| Item | Area | Today |
|---|---|---|
| R2DBC through the Cloud SQL connector | R2DBC | R2DBC connects to Cloud SQL directly, with IAM login; JDBC also works through the connector |
| A real-cloud run on MSK Serverless with IAM, including transactions | Streams | Tested on Kafka 3.9 and 4.2.1 brokers, and on real Event Hubs and Managed Kafka. The run is ready ([AWS tier 4](cloud-testing/aws.md#tier-4-not-run)) and needs an AWS account plan that includes MSK |
| A real-cloud run on Azure Database for MySQL with Entra ID | SQL databases | The run needs a directory-level grant in the test tenant |
| Fully non-blocking I/O for Google Cloud Storage, Service Bus receives, and Cosmos DB and Firestore read-then-write calls | Asynchronous I/O | These calls work and run on virtual threads, so they hold no platform thread while they wait; they become non-blocking when the SDKs allow it |

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
| 16 | Cloud logins | `Credentials.impersonate(target)`: an AWS IAM role through STS, with an external ID, a session name and a lifetime, or an impersonated Google service account, for every client and for SQL, cache and Kafka logins. `Credentials.servicePrincipal(...)`: an Entra ID application with a client secret, a certificate or a federated token file, for every Azure service. STS, SSO and SSO OIDC in `halyx-aws` for web identity (EKS) and SSO logins. Spring Boot `credentials.type: impersonate` and `service-principal`. Real clouds (tier 5): an assumed role with an external ID, an impersonated service account, and a service principal with a secret and with a federated Google ID token, on Boot 3.5 and 4. The certificate login is tested with unit tests only | [Provider configuration](providers.md#assumed-roles-and-impersonated-service-accounts) |
| 17 | Kotlin coroutines | `suspend fun` `@HalyxListener` methods for subscriptions and stream consumers; coroutine repositories with `suspend` CRUD and queries and `Flow` results, on `@JvmRecord` entities; `awaitSingle` and `asFlow` over the reactive services, with back-pressure and context. Tested on Boot 3.5 (Kotlin 1.9) and Boot 4 (Kotlin 2.3), in memory and on Kafka, SQS and DynamoDB emulators. Not yet released | [Kotlin coroutines](spring-boot.md#reactive-applications) |

| 18 | Function invocation | `FunctionInvoker`: AWS Lambda functions, Azure Functions and Cloud Run functions invoked by name with a payload and headers, with each cloud's login (IAM, Entra ID tokens or function keys, Google ID tokens) and the same exceptions on all three. Configured timeouts and headers, retries only for functions marked idempotent, fire-and-forget `trigger` on Lambda, `async()` and `ReactiveFunctionInvoker` views, trace context into the function, and Spring Boot `halyx.functions`. A function conformance kit on the memory provider, Floci's Lambda, the Azure Functions host and Google's Functions Framework; the real-cloud runs are ready in tier 1. Not yet released | [Functions](api/functions.md) |

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
