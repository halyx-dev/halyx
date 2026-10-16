---
title: Roadmap
nav_order: 8
---

# Roadmap
{: .no_toc }

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Done

| | Milestone | What it delivered |
|---|---|---|
| 1 | Core and object storage | the portable API, provider SPI, error model and interceptors; blob storage on S3, Azure Blob Storage and Cloud Storage; conformance kits and emulator tests |
| 2 | Caches | one Redis and Valkey driver with cloud logins: ElastiCache IAM, Entra ID, Memorystore IAM |
| 3 | SQL databases | a pooled `DataSource` with cloud IAM login and token refresh: RDS IAM, Entra ID, Cloud SQL, and the Cloud SQL connector |
| 4 | Parameters and secrets | SSM Parameter Store, Secrets Manager, App Configuration, Key Vault, Parameter Manager, Secret Manager and HashiCorp Vault; Spring configuration import with reload |
| 5 | NoSQL documents | DynamoDB, Cosmos DB and Firestore |
| 6 | Spring Boot | the starter, properties, auto-configuration, health, Micrometer, Boot 3.5 and 4 |
| 7 | Messaging | SNS and SQS, Service Bus, Pub/Sub; listeners, claim checks, tracing |
| 8 | Production gaps | NoSQL secondary indexes and transactions, batched acks, Service Bus sessions |
| 9 | Real-cloud testing | the conformance kits on real AWS, Google Cloud and Azure, in three tiers each, on throwaway infrastructure. [Results](cloud-testing/index.md) |
| 10 | Spring Data repositories | `halyx-spring-data-document`: Spring Data repositories over DynamoDB, Cosmos DB and Firestore, with records as entities, derived queries on keys and indexes, optimistic locking and keyset scrolling; Boot 3.5 and 4; passing on the emulators and on all three clouds. [Guide](spring-boot.md#spring-data-repositories) |

## Next

### Release 0.1.0

Publish the first release to Maven Central:

- [x] Apache-2.0 license, with headers on every source file
- [x] the `dev.halyx` group id
- [x] a full build and test pass, on emulators and on real clouds
- [ ] project metadata: URL, SCM and developers
- [ ] source and javadoc jars, signing, and the Central publishing plugin
- [ ] JPMS module descriptors
- [ ] continuous integration
- [ ] API compatibility checks with japicmp, from the release after 0.1.0

### Streams (managed Kafka)

`StreamProducer` and `StreamConsumer` on Kafka clusters, logging in with the cloud's identity the way caches and
SQL databases do. See [Streams](api/streams.md).

- [x] Amazon MSK, with IAM login
- [x] Azure Event Hubs through its Kafka endpoint, with Entra ID login
- [x] Google Cloud Managed Service for Apache Kafka, with Google IAM login
- [x] keys and order per partition, consumer groups, listeners with retries and dead letters, manual commits
- [x] idempotent and transactional producers as capabilities, claim checks, and Kafka's clients through `unwrap`
- [x] Spring Boot: properties, beans, `@HalyxListener`, health, metrics and tracing
- [x] conformance kit, on the in-memory provider and on Apache Kafka through every provider
- [x] the Kafka driver's broker tests on Kafka 4 brokers (4.2.1) as well as 3.9, on every build
- [x] real-cloud run on Event Hubs with Entra ID: [24/25, 1 skipped](cloud-testing/azure.md#tier-4-latest-result)
- [x] real-cloud run on Managed Service for Apache Kafka with Google IAM, from a runner inside the VPC:
  [25/25](cloud-testing/gcp.md#tier-4-latest-result)
- [ ] real-cloud run on MSK Serverless with IAM, including transactions: the script, Terraform and runner are
  ready, but the test account's AWS Free plan doesn't allow MSK ([AWS tier 4](cloud-testing/aws.md#tier-4-not-run))
- [x] Event Hubs: idempotent producers confirmed on a Standard namespace, now declared and on by default; Kafka 4
  clients pass the kit there
- [x] Spring for Apache Kafka (`KafkaTemplate`, `@KafkaListener`) with Halyx' cloud login
  (`halyx.spring-kafka.cluster`), on Boot 3 and 4, tested on Event Hubs and Managed Kafka
- [x] `kafkaClientProperties`: Halyx' connection and login for Kafka's own clients

### Event services

One API to publish events to the clouds' event routers, with CloudEvents as the portable format. Events are
received through the queue each router delivers to, with Halyx' subscriptions and listeners:

- [x] `CloudEvent` (CloudEvents 1.0) and `EventPublisher` in core, and one decoder for what each router delivers:
  EventBridge's envelope, CloudEvents JSON, and Pub/Sub's `ce-` attributes
- [x] Amazon EventBridge: publish to an event bus; a rule routes to SQS
- [x] Azure Event Grid: publish to a namespace topic, and receive from its pull subscriptions (a new
  `Subscription` driver, with acks, releases and lock renewal)
- [x] Google Cloud Eventarc Advanced: publish to a message bus; a pipeline routes to Pub/Sub
- [x] Spring Boot: `halyx.event-buses`, `EventPublisher` beans, and `@HalyxListener` methods taking a `CloudEvent`
- [x] conformance kit, on the in-memory provider, the emulators and the real clouds, with Spring Boot 3 and 4

### R2DBC with cloud IAM login

Reactive SQL (`halyx-r2dbc`): a pooled R2DBC `ConnectionFactory` whose new connections log in with the cloud's
identity, as `Database` does for JDBC, for Spring Data R2DBC and `DatabaseClient`:

- [x] `CloudContext.sqlEndpoint(SqlConfig)`: the host, database, TLS requirement and login a provider uses
- [x] a pooled `ConnectionFactory` (`r2dbc-pool`) for PostgreSQL and MySQL, logging each new connection in with
  a current RDS IAM, Entra ID or Cloud SQL IAM token, fetched off the event loop and cached until shortly
  before it expires; password logins, with rotation, too
- [x] Spring Boot: `halyx.r2dbc-databases`, `ConnectionFactory` beans that Spring Data R2DBC and `DatabaseClient`
  use, on Boot 3.5 and 4
- [x] tests on PostgreSQL and MySQL containers, and real-cloud runs on RDS, Azure Database for PostgreSQL and
  Cloud SQL, with Spring Data R2DBC on Boot 3 and 4 ([results](cloud-testing/index.md))
- [ ] the Cloud SQL connector's R2DBC adapters

SQL Server stays password-only over R2DBC: its driver takes no access tokens.

### Reactive adapters: `halyx-reactor`

Halyx stays blocking, and the application chooses the threads: [Reactive and async code](api/reactive.md)
shows the patterns, tested on Boot 3.5 and 4. An optional module would package the parts that are easy to get
wrong, always on a scheduler the application passes in, never one of Halyx's own:

- [x] the guide: single calls on the application's scheduler, fan-out with a limit, paged queries and
  subscriptions as a `Flux` (acked after processing, nacked when cancelled), context propagation, WebFlux
- [ ] `HalyxReactor.wrap(service, scheduler)`: `Mono` and `Flux` views of every service on the given scheduler
- [ ] streaming adapters: subscriptions and stream consumers as a `Flux` with acks or commits after
  processing and back-pressure through the consumer's concurrency; listings, queries and blob bodies as a `Flux`
- [ ] reactive Spring Data repositories (`ReactiveCrudRepository`) over document stores
- [ ] WebFlux tests on Boot 3.5 and 4, and the conformance kits run through the adapters
- [ ] non-blocking drivers on the clouds' async SDK clients (Azure, AWS, Lettuce first), only where
  benchmarks show the blocking path on virtual threads is the bottleneck

### Portable Spring Boot microservices

One Spring Boot sample service, built once, that runs unchanged on AWS, Google Cloud and Azure: only its
configuration says which cloud it is on. It uses each Halyx API (blobs, documents, messaging, streams, caches,
SQL, parameters and secrets), and the same end-to-end tests run against every deployment.

- [ ] a sample service and its end-to-end tests, with the cloud chosen only by Spring profiles and properties
- [ ] the same image on each cloud, with no cloud-specific code and no rebuild
- [ ] the results and any differences between clouds in [Cloud testing](cloud-testing/index.md)

### Microservices on Kubernetes: EKS, GKE and AKS

The portable service on each cloud's managed Kubernetes, logging in with the platform's workload identity and
no stored keys, and then spread across clouds:

- [ ] **portable:** the same image and Helm chart on EKS (EKS Pod Identity or IRSA), GKE (Workload Identity
  Federation for GKE) and AKS (Microsoft Entra Workload ID), each using its own cloud's services
- [ ] **hybrid:** services on one cluster using another cloud's services, for example a pod on GKE reading S3,
  or a pod on AKS consuming Pub/Sub, through cross-cloud identity federation rather than keys
- [ ] **hybrid:** one application split across clusters on different clouds, talking through streams or
  messaging
- [ ] throwaway clusters per run, with Terraform and the same always-tear-down scripts as the other tiers

### Real-cloud test follow-ups

- [ ] AWS tier 3: check the ElastiCache `Connect` permission scoping in the test IAM policy
- [ ] Azure Database for MySQL with Entra ID, which needs a directory-level grant for the server's identity

## On demand

Planned, and prioritised by demand:

- **Aurora fast failover** through the AWS Advanced JDBC Wrapper.
- **Nested field paths** in NoSQL updates.
- **Cross-instance cache stampede protection**: a single-flight `getOrLoad`.
- **Object storage extras**: archive restore, KMS encryption, versioning, tags and retention.

## Not planned

These were considered and dropped, because they work already or don't earn their weight:

| Idea | Why not |
|---|---|
| cache endpoint discovery through cloud APIs | you configure the endpoint; discovery adds permissions and failure modes |
| push notifications for parameter changes | polling `watch` and configuration reload cover it |
| SSM and Secret Manager labels | rarely needed; `unwrap` covers them |
| GCS parallel composite uploads, the AWS CRT client, an async API | the parallel transfer engines already cover large objects |
| RDS Proxy support | nothing to add: point `host` at the proxy endpoint, with IAM login if it requires it |
