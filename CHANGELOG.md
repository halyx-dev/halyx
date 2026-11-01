# Changelog

## 1.1.0 (unreleased)

### Features

- **Kotlin coroutines** ([#11](https://github.com/halyx-dev/halyx/issues/11)):
  - `@HalyxListener` methods may be `suspend fun`s, for subscriptions and stream consumers. Returning acks or
    commits, and throwing retries, as with any listener. Needs `kotlinx-coroutines-reactor`.
  - Reactive repositories take `suspend` CRUD methods and derived queries, and `Flow` results: a `Flow` query reads
    every match, page by page, and a `suspend` query may return a `Window`. Entities are `@JvmRecord data class`es.
  - Tested on Boot 3.5 (Kotlin 1.9) and Boot 4 (Kotlin 2.3), in memory and on Kafka, SQS and DynamoDB emulators,
    with `awaitSingle` and `asFlow` over the reactive services.

- **Function invocation** ([#22](https://github.com/halyx-dev/halyx/issues/22)):
  - `FunctionInvoker` invokes AWS Lambda functions, Azure Functions and Cloud Run functions by name, with a payload,
    configured and per-request headers, and a timeout, through `CloudContext.functionInvoker`. Each cloud's login is
    handled: IAM on Lambda, an Entra ID token or a function key on Azure, a Google ID token on Cloud Run.
  - A function that throws raises `UnavailableException` on every cloud, a missing one `NotFoundException`, and
    other statuses map as for every other service. Requests over a cloud's payload or header limit are refused
    before they're sent.
  - Invocations run the function, so they're retried only for functions configured `idempotent`; the SDKs retry
    others only after throttling or a failure to connect.
  - `trigger` starts a Lambda function without waiting for it (`FunctionCapability.TRIGGER`). `async()` views, and
    `ReactiveFunctionInvoker` in halyx-reactor.
  - `TracingFunctionInvoker` carries the trace context into the function, as headers or in Lambda's `ClientContext`.
  - Spring Boot: `halyx.functions.<key>` registers a `FunctionInvoker` bean, with a reactive view.
  - A function conformance kit, run on the memory provider (`InMemoryFunctions`), Floci's Lambda, the Azure
    Functions host and Google's Functions Framework, with tier 1 real-cloud runs on all three clouds.
  - `AzureErrors.fromStatus` and `GcpErrors.fromStatus` map an HTTP status as the SDK translations do.
  - The AI skills know functions: `halyx-ai-create` plans, writes and tests function calls, `halyx-ai-migrate` maps
    `LambdaClient` and HTTP calls to Azure Functions and Cloud Run functions, and `halyx-ai-review` checks them
    (rule C11).

- **Rotated keys** ([#26](https://github.com/halyx-dev/halyx/issues/26)):
  - `FunctionConfig.Builder.property(String, Supplier<String>)`: a property asked for each time it's used. The
    Azure function key is asked for on every call, so a rotated key takes effect without a restart.
  - Spring Boot: `halyx.functions.<key>.secrets` reads settings such as `azure.functions.key` from one of the
    `halyx.parameter-stores`, through its read cache. A missing secret fails the start.
  - Spring Boot: a `halyx.*` property whose `${...}` placeholder didn't resolve fails the start, naming the property.
    Spring leaves such a value as written, so a missing secret was sent as the key and failed every call.
  - Cosmos DB account keys and Event Grid access keys given as suppliers (`ProviderConfig.property(String, Supplier)`,
    and the same on `EventBusConfig` and `SubscriptionConfig`) are asked for every `azure.key-refresh-interval` (one
    minute by default), and Cosmos DB and Event Grid use a rotated key from the next request. Spring Boot:
    `secrets` on providers, event buses and subscriptions.
  - Service Bus and App Configuration connection strings stay read once: their SDKs copy the key when the client is
    built. The docs recommend Entra ID login for keys that rotate.
  - Cache and Kafka passwords given as suppliers (`CacheAuth.password(Supplier)`,
    `StreamAuth.password(mechanism, username, Supplier)`) are asked for each time a connection logs in: Redis on
    new connections and reconnects, Kafka also when the broker has open connections log in again. Spring Boot:
    `auth.secret` on caches, the cache manager and stream clusters, as on databases, including for Spring for
    Apache Kafka.
  - Docs: keeping keys for other services in a parameter store, including a store on another cloud.
  - Spring Boot: a blank `properties` value no longer counts as set next to a `secrets` entry for the same setting,
    as a blank value is unset everywhere else.
  - Real-cloud tests on Azure (tier 1): a function key, a Cosmos DB key and an Event Grid key rotated in Key Vault
    while the clients stay open, and the function key through Spring Boot `secrets`.

- **Function error text** ([#31](https://github.com/halyx-dev/halyx/issues/31)): a failed invocation's exception
  has a `FunctionError` cause with what the function reported, whole: Lambda's `errorMessage`, `errorType` and
  payload, or an HTTP function's status and body. `FunctionError.of(e)` finds it. The exception messages are
  unchanged and still cut long bodies. On the memory provider, a handler's exception is now the `FunctionError`'s
  cause rather than the `UnavailableException`'s.

### Build

- **API compatibility checks** ([#4](https://github.com/halyx-dev/halyx/issues/4)): `mvn verify` compares each
  published module with 1.0.0 from Maven Central using japicmp, and fails on a binary- or source-incompatible change
  to the public API, the provider SPI included. CI runs it on every push.

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
