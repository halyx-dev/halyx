# Changelog

## 1.0.2 (2026-10-25)

### Fixes

- **JSON cache codec on Spring Boot 4** ([#9](https://github.com/halyx-dev/halyx/issues/9)): with
  `halyx.cache-manager.codec=json` and Jackson 2 on the classpath too, as `halyx-azure` brings it, the codec used
  Jackson 2 without Java date/time support, so caching a value with an `Instant` failed. Jackson 3 is now always
  preferred when present.
- **Cancelling a reactive call** ([#12](https://github.com/halyx-dev/halyx/issues/12)): cancelling a `ReactiveHalyx`
  call that had started, by `timeout()` for example, interrupted its thread on the default virtual-thread scheduler
  and on `boundedElastic`, which could abort a blocking SDK call partway through. A started call now runs to
  completion and its result is dropped, as documented; one cancelled before it starts never runs. The docs now also
  say that calls on the non-blocking path are cancelled with the subscription, and the driver may abort the request.

### Samples

- **Plain Java quickstart** ([#2](https://github.com/halyx-dev/halyx/issues/2)): `halyx-samples/plain-java-quickstart`
  object storage, messaging and parameters without Spring, on the in-memory provider by default or on a cloud through
  system properties; linked from the README and Getting started. Contributed in
  [#8](https://github.com/halyx-dev/halyx/pull/8).

## 1.0.1 (2026-10-23)

### Fixes

- **Gradle with Spring Boot 3.5:** `halyx-aws`, `halyx-azure` and `halyx-gcp` failed to resolve, because `halyx-kafka`
  brought both the patched `at.yawk.lz4:lz4-java` and the `org.lz4:lz4-java` that Boot 3.5's `kafka-clients` depends
  on, and Gradle refuses two modules with the same capability. `halyx-kafka` now excludes `org.lz4:lz4-java`, so
  only the patched one is on the classpath, with Maven too.
- **`ReactiveBlobStore.getBody`** closes the blob before the completion or error reaches the subscriber, as
  documented, rather than just after it.

## 1.0.0 (2026-10-23)

The first release: one Java API for object storage, NoSQL documents, messaging, events, streams, SQL databases,
caches, and parameters and secrets on AWS, Azure and Google Cloud, over each cloud's own SDKs.

### Requirements

- Java 21 or later.
- For Spring Boot applications, Spring Boot 3.5 or 4.

### Modules

| Module | What it adds |
|---|---|
| `halyx-bom` | One version for every Halyx module, and for the Cloud SQL connector |
| `halyx-core` | The portable API, provider SPI, error model, interceptors and an in-memory provider |
| `halyx-aws` | S3, DynamoDB, SNS, SQS, EventBridge, SSM Parameter Store, Secrets Manager, ElastiCache, RDS and MSK IAM login |
| `halyx-azure` | Blob Storage, Cosmos DB, Service Bus, Event Grid namespaces, Key Vault, App Configuration, Event Hubs, and Entra ID login for caches and databases |
| `halyx-gcp` | Cloud Storage, Firestore, Pub/Sub, Eventarc Advanced, Secret Manager, Parameter Manager, Managed Kafka, and IAM login for Memorystore and Cloud SQL |
| `halyx-vault` | HashiCorp Vault KV v2 parameters and secrets |
| `halyx-redis`, `halyx-sql`, `halyx-r2dbc`, `halyx-kafka` | Redis and Valkey caches, a pooled JDBC `DataSource`, a pooled R2DBC `ConnectionFactory`, and Kafka streams, each with cloud IAM login |
| `halyx-reactor` | `Mono` and `Flux` views of every service |
| `halyx-micrometer` | Observations of every operation, and trace context carried through messages, events and stream records |
| `halyx-spring-data-document` | Spring Data repositories over DynamoDB, Cosmos DB and Firestore |
| `halyx-spring-boot-starter` | Spring Boot auto-configuration, on Boot 3.5 and 4 |
| `halyx-tck` | The conformance kits, for testing a provider |

Each jar has a Java module name, such as `dev.halyx` for `halyx-core` and `dev.halyx.aws` for `halyx-aws`.

### Highlights

- **Object storage** on S3, Azure Blob Storage and Cloud Storage, with parallel transfers, presigned URLs and
  conditional writes.
- **NoSQL documents** on DynamoDB, Cosmos DB and Firestore, with secondary indexes and transactions, and Spring Data
  repositories with derived queries, optimistic locking and keyset scrolling.
- **Messaging** on SNS and SQS, Service Bus and Pub/Sub: listeners, batched acknowledgements, sessions, claim checks
  and tracing.
- **Events** as CloudEvents 1.0, published to EventBridge, Event Grid namespace topics and Eventarc Advanced.
- **Streams** on Amazon MSK, Event Hubs and Managed Service for Apache Kafka, with consumer groups, retries, dead
  letters, and idempotent and transactional producers; Spring for Apache Kafka with Halyx's cloud login.
- **SQL databases** through JDBC and R2DBC, logging in with RDS IAM, Entra ID or Cloud SQL IAM, or through the Cloud
  SQL connector.
- **Caches** on ElastiCache, Azure Cache for Redis, Azure Managed Redis and Memorystore, with cloud IAM login, and a Spring
  `CacheManager`.
- **Parameters and secrets** from SSM Parameter Store, Secrets Manager, App Configuration, Key Vault, Parameter
  Manager, Secret Manager and Vault, with Spring configuration import (`halyx:`) and reload.
- **Credentials:** each cloud's default chain, plus assumed AWS roles, impersonated Google service accounts and
  Entra ID service principals, including a federated token from another cloud, with no stored secret.
- **Spring Boot:** properties for every service, health indicators, Micrometer metrics, and `@HalyxListener` methods.
- **Reactive and asynchronous APIs** for every service, non-blocking where the SDK's async client allows.
- **A portable microservice sample** that runs one image unchanged on EKS, GKE and AKS.
- **AI skills** for migrating a Spring Boot service to Halyx, reviewing one for portability, and creating a new one.

### Testing

Every service passes the conformance kits on the in-memory provider, on each cloud's emulators and on the real
clouds, with Spring Boot 3.5 and 4. The latest results are in the
[cloud testing reports](https://halyx.dev/docs/cloud-testing/).

### Known limitations

- Not yet run on real clouds: MSK Serverless (the test account's plan doesn't allow it), Azure Database for MySQL with
  Entra ID, and the service principal's certificate login.
- Some calls still block underneath, on a virtual thread: Cloud Storage, Service Bus receives, and Cosmos DB and
  Firestore read-then-write calls.
- R2DBC to SQL Server is password-only, because its driver takes no access tokens.

See the [roadmap](https://halyx.dev/docs/roadmap.html) for what comes next.
