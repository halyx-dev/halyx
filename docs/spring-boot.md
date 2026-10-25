---
title: Spring Boot
nav_order: 5
---

# Spring Boot
{: .no_toc }

The Halyx starter turns `halyx.*` properties into ready-to-inject beans, with health checks, metrics,
tracing, `@HalyxListener` message handlers and `spring.config.import` for parameters and secrets. It
supports **Spring Boot 3.5 and 4** (Java 21) with the same starter.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Setup

Add the starter plus the provider modules you use:

```xml
<dependency>
  <groupId>dev.halyx</groupId>
  <artifactId>halyx-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>dev.halyx</groupId>
  <artifactId>halyx-aws</artifactId>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation(platform("dev.halyx:halyx-bom:1.0.2"))
    implementation("dev.halyx:halyx-spring-boot-starter")
    implementation("dev.halyx:halyx-aws")
}
```

Gradle (Groovy):

```groovy
dependencies {
    implementation platform('dev.halyx:halyx-bom:1.0.2')
    implementation 'dev.halyx:halyx-spring-boot-starter'
    implementation 'dev.halyx:halyx-aws'
}
```

(versions come from the `halyx-bom`; see [Getting started](getting-started.md#add-the-dependencies))

Then describe your providers and resources:

```yaml
halyx:
  providers:
    aws: { type: aws, region: eu-west-2 }
  blob-stores:
    invoices: { bucket: acme-invoices }
  topics:
    order-events: { name: orders }
  subscriptions:
    billing: { name: orders-billing }
```

```java
@Service
class InvoiceService {
    private final BlobStore invoices;
    private final Publisher events;

    InvoiceService(@Qualifier("invoices") BlobStore invoices, @Qualifier("order-events") Publisher events) {
        this.invoices = invoices;
        this.events = events;
    }

    void store(Invoice invoice) {
        invoices.put(BlobKey.of("invoices/" + invoice.id() + ".pdf"), BlobContent.ofBytes(invoice.pdf()));
        events.publish(OutgoingMessage.builder(invoice.json()).attribute("type", "invoiced").build());
    }
}

@Component
class Billing {
    @HalyxListener("billing")
    void onOrder(String json) {        // return = ack, throw = retry with backoff
        bill(json);
    }
}
```

Moving to Azure or Google Cloud means changing the provider `type` and the resource names in
configuration. The code stays the same.

## Providers

```yaml
halyx:
  providers:
    orders:
      type: aws                    # aws | azure | gcp | vault | memory
      region: eu-west-2
      # endpoint: http://localhost:4566
      # credentials: { type: static, id: ..., secret: ... }   # default | static | impersonate | service-principal | anonymous
      properties:                  # provider settings: see Provider configuration
        http.max-connections: 256
```

Assumed roles, impersonated service accounts and Entra ID service principals are set under `credentials`:

```yaml
halyx:
  providers:
    partner-account:
      type: aws
      credentials:
        type: impersonate
        target: arn:aws:iam::123456789012:role/orders     # a service account email on gcp
        external-id: acme                                 # optional, AWS only
        session-name: billing                             # optional, AWS only
        lifetime: 30m                                     # optional
        # id: ... and secret: ...                         # an access key to start from, instead of the default chain (AWS)
    azure-from-eks:
      type: azure
      credentials:
        type: service-principal
        tenant-id: 00000000-0000-0000-0000-000000000000
        client-id: 11111111-1111-1111-1111-111111111111
        token-file: /var/run/secrets/eks.amazonaws.com/serviceaccount/token   # or secret: ..., or certificate: app.pem
```

Each entry is a named provider, like `Halyx.builder().register(name, config)`. When only one provider is
registered, resources use it without saying so; with several, each resource names its `provider`. Resources can
also name providers that a `HalyxBuilderCustomizer` registers, or your own `Halyx` bean has: names are resolved
against the `Halyx` bean when each resource bean is created. (`spring.config.import` runs before any bean exists, so
the stores it imports need their provider under `halyx.providers`.) See [Provider configuration](providers.md) for
every property.

## Object storage

```yaml
halyx:
  blob-stores:
    invoices:                      # BlobStore bean name
      bucket: acme-invoices        # defaults to the bean name
      # provider: orders           # optional when only one provider is configured
```

## NoSQL documents

```yaml
halyx:
  document-stores:
    orders:                        # DocumentStore bean name
      table: orders-v2             # DynamoDB table, Cosmos container or Firestore collection; defaults to the key
      sort-key: sk                 # omit for tables keyed by partition only
      indexes:
        by-status: { partition-attribute: status, sort-attribute: placedAt }
```

### Spring Data repositories

With `halyx-spring-data-document` on the classpath, repository interfaces in the application's packages become beans over
these document stores. Entities are records: the key components are annotated, and the rest of the record is the
document.

```xml
<dependency>
  <groupId>dev.halyx</groupId>
  <artifactId>halyx-spring-data-document</artifactId>
</dependency>
```

```java
@DocumentTable("orders")                       // a halyx.document-stores bean; optional if there is only one
record Order(@PartitionKey String customerId,
             @SortKey String orderId,
             String status,
             String placedAt,
             BigDecimal total,
             @Version dev.halyx.nosql.Version version) { }   // Spring Data's @Version: optimistic writes

interface OrderRepository extends DocumentRepository<Order, Key> {     // Key for tables with a sort key, else String
    List<Order> findByCustomerId(String customerId);
    List<Order> findByCustomerIdAndOrderIdBetweenOrderByOrderIdDesc(String customerId, String from, String to);
    Optional<Order> findFirstByCustomerIdOrderByOrderIdDesc(String customerId);
    Window<Order> findByCustomerId(String customerId, ScrollPosition position, Limit limit);
    Stream<Order> findByStatus(String status);                         // the by-status index
    List<Order> findByStatusAndPlacedAtGreaterThanEqual(String status, String from);
    boolean existsByStatus(String status);
}
```

| | |
|---|---|
| CRUD | `save`, `saveAll`, `findById`, `existsById`, `findAllById`, `deleteById`, `delete`, `deleteAllById`, `deleteAll`. Unversioned `saveAll` writes in batches |
| Keys | `@PartitionKey`, and `@SortKey` for tables with a sort key: `String`, `UUID`, `LocalDate` or an enum, stored in the table's key attributes. Sort keys compare as strings, so use fixed-width values |
| Optimistic locking | with a `@Version` component of type `dev.halyx.nosql.Version`, `save` inserts an entity without a version only if its id is free (`DuplicateKeyException`), and writes or deletes one with a version only if the stored item still has it (`OptimisticLockingFailureException`). `save` returns the entity with its new version. On Firestore, a save that changes nothing keeps the version |
| Values | as in [records](api/nosql.md#records). Cosmos DB and Firestore keep decimals as doubles, so a `BigDecimal` can read back with another scale (`10` as `10.0`), and records holding one may not be `equals` to what was saved |
| Derived queries | by the partition key, with at most one condition on the sort key (`Is`, `Between`, `StartingWith`, `GreaterThan(Equal)`, `LessThan(Equal)`, `After`, `Before`); or by the partition attribute of a declared index, with at most one condition on its sort attribute. Ordered by that sort attribute, ascending or `Desc`. `First`/`Top` and a `Limit` parameter cap the results; `exists` queries too |
| Results | `List`, `Stream` (read page by page as it's consumed), a single entity or `Optional`, and `Window` with a `ScrollPosition` parameter: start from `ScrollPosition.keyset()` and continue from `window.positionAt(window.size() - 1)` |
| Fails at startup | anything that would scan the table or skip by offset: conditions on other attributes, `Or`, `IgnoreCase`, nested properties, `count` and derived deletes, `Pageable`, `Slice`, `Page` and `Sort` parameters, projections, and `findAll`, since `DocumentRepository` has none |

Index queries are eventually consistent on DynamoDB, as with `queryIndex`. There's no general `@Transactional`:
use `DocumentStore.transact` for atomic writes across items. Without Spring Boot, `@EnableHalyxRepositories`
creates the repositories over the application's `DocumentStore` beans; turn the auto-configuration off with
`halyx.document-repositories.enabled=false`. Halyx repositories work alongside other Spring Data modules, such as
JPA, JDBC, Spring Data Cosmos or Spring Cloud GCP's Firestore: each module claims only the repositories that
extend its own repository types, so a service can move its entities to Halyx one at a time. Both work with the
Spring Data each Boot version manages (2025.0 on Boot 3.5,
2026.0 on Boot 4).

## Messaging

```yaml
halyx:
  topics:
    order-events:                  # Publisher bean name
      name: orders                 # SNS topic, Service Bus topic or Pub/Sub topic; defaults to the key
      claim-check: { blob-store: invoices, threshold: 204800 }
  subscriptions:
    billing:                       # Subscription bean name, used by @HalyxListener("billing")
      name: orders-billing         # SQS queue, <topic>/subscriptions/<name> or Pub/Sub subscription
      listener: { concurrency: 32, max-backoff: 2m }
```

`@HalyxListener("<subscription bean>")` methods take a `String` body, a `byte[]` body, a `CloudEvent` or the whole
`ReceivedMessage`. Returning acks the message; throwing nacks it for redelivery with backoff. Override
the subscription's concurrency per listener with `@HalyxListener(value = "billing", concurrency = 8)`.
Every listener is checked before any starts, so a mistake, such as a listener naming no subscription, fails startup
with nothing consuming; and if one fails to start, those already started are stopped.

Listeners start once the application is ready, and stop first on shutdown, letting running handlers
finish. Each consumer reports metrics and appears under the `messaging` health entry. Wrap every
listener, for example for MDC or tenancy, with a `MessageListenerDecorator` bean.

## Events

```yaml
halyx:
  event-buses:
    order-events:                  # EventPublisher bean name
      name: orders                 # EventBridge bus, Event Grid namespace topic or Eventarc message bus; defaults to the key
      properties: { azure.eventgrid.endpoint: "https://acme.australiaeast-1.eventgrid.azure.net" }
  subscriptions:
    billing:
      name: orders-inbox           # the queue the router delivers to
```

`@HalyxListener` methods on a subscription can take a `CloudEvent`; each message is decoded with
`CloudEvents.decode`, and one that holds no CloudEvent fails like a throwing handler, so it is retried and in time
dead-lettered. See [Events](api/events.md).

```java
@HalyxListener("billing")
void onOrderEvent(CloudEvent event) { ... }
```

## Streams

```yaml
halyx:
  stream-clusters:
    events:
      provider: orders               # optional when only one provider is configured
      bootstrap-servers: boot-abc123.c2.kafka-serverless.eu-west-2.amazonaws.com:9098
      auth: { type: iam }            # none, password (mechanism, username, password) or iam
      properties: { kafka.linger.ms: 5 }
  stream-producers:
    order-events:                    # StreamProducer bean name
      topic: orders                  # defaults to the key
      idempotent: true
    order-events-dlt:
      topic: orders-dlt
  stream-consumers:
    billing:                         # StreamConsumer bean name, used by @HalyxListener("billing")
      topic: orders
      group: billing                 # defaults to the key
      start-position: earliest
      listener: { max-attempts: 5, dead-letter: order-events-dlt, max-backoff: 1m }
```

`cluster` may be omitted on producers and consumers when only one stream cluster is configured.
`@HalyxListener("<stream consumer bean>")` methods take a `String` value, a `byte[]` value or the whole
`ReceivedRecord`. Returning commits the record; throwing retries it with backoff, holding back the rest of its
partition, until `max-attempts` sends it to the `dead-letter` producer. A stream consumer is one consumer group
member, so it runs one listener, without `concurrency`: its parallelism is its partitions. Startup fails if two
methods name the same consumer, or one sets `concurrency`; give each further listener its own
`halyx.stream-consumers` entry and group. Stream consumers leave their group when
the application stops. Each listener reports metrics and appears under the `streams` health entry, and each
producer has its own health indicator. Wrap every stream listener with a `RecordListenerDecorator` bean.

### Spring for Apache Kafka

Applications that use Spring for Apache Kafka (`KafkaTemplate`, `@KafkaListener`) can log in to a stream
cluster with Halyx instead of configuring SASL themselves:

```yaml
halyx:
  spring-kafka:
    cluster: events                  # one of halyx.stream-clusters
spring:
  kafka:
    consumer: { auto-offset-reset: earliest }
```

```java
@Autowired KafkaTemplate<String, String> template;

@KafkaListener(topics = "orders", groupId = "shipping")
void onOrder(String order) { ... }
```

Every `DefaultKafkaProducerFactory` and `DefaultKafkaConsumerFactory` bean, Boot's or your own, gets the
cluster's bootstrap servers and login: MSK IAM, Entra ID or Google IAM tokens, refreshed before they expire.
Halyx' settings go on top of `spring.kafka.*`, so leave out `spring.kafka.bootstrap-servers` and the
`security` and `sasl` settings; serializers, groups and listener settings stay Spring's. Boot's `KafkaAdmin`
keeps its own settings, so create topics outside the application, as with Halyx' own streams. Without
`halyx.spring-kafka.cluster`, Spring for Apache Kafka is left alone. This needs `spring-kafka` on the classpath
(`spring-boot-starter-kafka` on Boot 4), and works with the version each Boot manages.

## SQL databases

```yaml
halyx:
  databases:
    orders:                        # DataSource bean name; a single database is the primary DataSource
      engine: postgresql           # postgresql | mysql | sqlserver
      host: orders.cluster-abc.eu-west-1.rds.amazonaws.com
      database: orders
      auth: { type: iam, username: orders_app }    # RDS IAM, Entra ID or Cloud SQL IAM tokens
      # auth: { type: password, username: app, secret: { store: app-secrets, name: orders/db, field: password } }
      pool: { max-size: 20, min-idle: 5 }
```

Every entry becomes a `DataSource` bean, covered by Spring Boot's JDBC health check and Hikari metrics.
Halyx configures itself before Boot's `DataSourceAutoConfiguration`, which then backs off. Spring Data
JPA and JDBC repositories, `JdbcTemplate`, Flyway and Liquibase all use the primary Halyx `DataSource`
unchanged. With several databases, mark one `primary: true`, and wire extra `EntityManagerFactory` beans
yourself, as in plain Spring Boot. Pool settings live under `halyx.databases.<name>.pool`, not
`spring.datasource.*`.

A password `secret` reference reads the password from a parameter store for every new connection, so
rotated secrets take effect without a restart.

### R2DBC and Spring Data R2DBC

With `halyx-r2dbc` and an R2DBC driver on the classpath, `halyx.r2dbc-databases` takes the same settings as
`halyx.databases` and registers a pooled R2DBC `ConnectionFactory` bean for each entry:

```yaml
halyx:
  r2dbc-databases:
    orders:                        # ConnectionFactory bean name
      engine: postgresql           # postgresql or mysql with iam; sqlserver with a password only
      host: orders.cluster-abc.eu-west-1.rds.amazonaws.com
      database: orders
      auth: { type: iam, username: orders_app }
      pool: { max-size: 20 }
```

Boot's own R2DBC auto-configuration backs off, and Spring Data R2DBC repositories, `R2dbcEntityTemplate` and
`DatabaseClient` use the Halyx pool, on Boot 3.5 and 4. With several, mark one `primary: true`. Each new
connection logs in as with JDBC: a password looked up every time, or the cloud's IAM token, fetched off the
event loop. The pool is disposed of with the context.

## Caches

```yaml
halyx:
  caches:
    sessions:                      # Cache<String> bean name, also the default key namespace
      endpoint: rediss://master.sessions.abc123.euw2.cache.amazonaws.com:6379
      default-ttl: 30m
      auth: { type: iam, username: sessions-app }      # none | password | iam
      properties: { aws.elasticache.cache-name: sessions }
    quotes:
      endpoint: rediss://master.sessions.abc123.euw2.cache.amazonaws.com:6379
      codec: json                  # a Cache<Object>; see below
      trusted-packages: com.example.quotes
```

`codec` decides how values are stored, and so the bean's type: `string` (the default, a `Cache<String>`), `bytes`
(a `Cache<byte[]>`), `jdk` or `json` (a `Cache<Object>` that reads back only `java.*` types and those in
`trusted-packages`, as for [`@Cacheable`](#springs-cacheable)), or the name of a `Codec<V>` bean of yours, which
makes a `Cache<V>` that injects by its type. The health check pings the server even when the cache is
`fail-open`, so an outage shows as DOWN.

### Spring's `@Cacheable`

`halyx.cache-manager.*` backs Spring's caching annotations, `@Cacheable`, `@CachePut` and `@CacheEvict`
(with `@EnableCaching`). Boot's own cache auto-configuration then backs off, so `spring.cache.*` does not apply;
a `CacheManager` bean of yours takes precedence over both:

```yaml
halyx:
  cache-manager:
    endpoint: rediss://master.app.abc123.euw2.cache.amazonaws.com:6379
    auth: { type: password, password: ${CACHE_AUTH_TOKEN} }
    default-ttl: 10m
    ttl: { users: 1h }             # per-cache override
    namespace-prefix: "orders-svc:"
    fail-open: true                # cached methods run uncached while the cache is down
    codec: json                    # jdk (default) | json (Jackson 3 on Boot 4, Jackson 2 on Boot 3)
    trusted-packages: com.example.orders
```

The `halyx` health contributor has a `cache-manager` entry, which pings the server with the manager's login, even
with `fail-open`, so an outage shows as DOWN rather than as cached methods quietly running uncached.

It supports `allEntries` evictions, `sync = true`, per-cache TTLs and cached nulls. The JSON codec stores
`{"@class", "v"}` and only reads back types from `trusted-packages`, with no unsafe polymorphic typing.
The JDK codec applies the same allow-list through an `ObjectInputFilter`.

Methods returning `CompletableFuture`, `Mono` or `Flux` are cached too (Spring 6.1's `Cache.retrieve`), with
`sync = true` and `@CacheEvict`. The cache's calls to the server run on an executor rather than the caller's
thread: virtual threads by default, or an `Executor` bean named `halyxCacheExecutor`. Spring writes and
evicts a reactive method's result from inside its pipeline, so on a Reactor non-blocking thread, such as a
Netty event loop, the write is handed to that executor and lands just after the method's value is emitted;
everywhere else writes complete before returning, as before.

## Parameters and secrets

```yaml
halyx:
  parameter-stores:
    app-secrets:                   # ParameterStore bean name, and the name used by halyx: imports
      kind: secrets                # secrets | parameters
      prefix: orders-svc
      cache-ttl: 5m
      reload-interval: 1m          # for halyx: imports; omit to read once at startup
      # native-names: true         # use existing backend names as they are
      # expand-json: true          # (default) JSON values also become db.creds.password and so on
      # startup-attempts: 3        # (default) retries while the backend is briefly unavailable
```

### Configuration import

Load a store into the Spring `Environment` at startup:

```yaml
spring:
  config:
    import: halyx:app-secrets     # or halyx:app-secrets/db, or optional:halyx:app-secrets
```

```java
@Value("${db.password}") String password;      // the secret orders-svc/db/password
```

- Names become property keys, with `/` replaced by `.`, so `db/password` becomes `db.password`. JSON
  values add their fields too, such as `db.creds.password`.
- The providers and stores must be defined in the same configuration file or an earlier one.
- A briefly unavailable backend is retried with backoff (`startup-attempts`).
- With `reload-interval`, imported properties are updated in place when values change or rotate. Halyx
  publishes a `HalyxParametersChangedEvent` with the store name and changed keys, and Spring Cloud's
  `EnvironmentChangeEvent` when Spring Cloud Context is present. `@ConfigurationProperties` and
  `@RefreshScope` beans then follow. Without Spring Cloud, `Environment` reads and event listeners see
  changes, and `@Value` fields keep their startup values.
- `halyx.config-import.enabled=false` makes every `halyx:` import load nothing, optional or not, without
  connecting. Imports run while Spring prepares the environment, with the machine's own credentials, so a test that
  resolves a cloud profile as written, or a tool that starts the application with no cloud, sets it on the command
  line or in the environment (`HALYX_CONFIGIMPORT_ENABLED=false`).

## Reactive applications

With `halyx-reactor` on the classpath, every service bean from `halyx.*` properties also has a reactive view
([Reactive and async code](api/reactive.md)): a bean named `<name>Reactive`, qualified `<name>`, so it injects
as the blocking bean does.

```java
@RestController
class InvoiceController {
    private final ReactiveBlobStore invoices;

    InvoiceController(@Qualifier("invoices") ReactiveBlobStore invoices) {
        this.invoices = invoices;
    }

    @GetMapping("/invoices/{name}")
    Mono<byte[]> invoice(@PathVariable String name) {
        return invoices.getBytes(BlobKey.of(name));
    }
}
```

A `ReactiveHalyx` bean makes them, and wraps anything else (`reactiveHalyx.wrap(...)`, or `call` and `stream`
for other blocking work):

```yaml
halyx:
  reactor:
    scheduler: virtual-threads     # default; or bounded-elastic, or the name of a Scheduler bean of yours
    max-concurrency: 64            # most calls in flight across the reactive beans; 0 (default) for no limit
    enabled: true                  # false: no reactive beans
```

The virtual-thread scheduler is disposed of with the context; a `Scheduler` bean of yours stays yours.

**Listeners.** `@HalyxListener` methods may return a `Mono`, any Reactive Streams `Publisher` or a
`CompletableFuture`. The listener waits for it on its virtual thread, so completing acks the message (or commits
the record) and failing retries it, exactly as returning and throwing do, within the subscription's
concurrency.

```java
@HalyxListener("billing")
Mono<Void> onOrder(CloudEvent placed) {
    return invoices.put(BlobKey.of(placed.subject().orElseThrow()), render(placed)).then();
}
```

**Repositories.** Interfaces extending `ReactiveDocumentRepository` in the application's packages become
beans over `halyx.document-stores`, next to blocking `DocumentRepository` ones, with the same derived queries
returning `Flux` and `Mono`; see [Spring Data repositories](#spring-data-repositories).
`halyx.document-repositories.enabled=false` turns both off. Without Spring Boot, use
`@EnableHalyxReactiveRepositories` and declare a `ReactiveHalyx` bean.

```java
interface Orders extends ReactiveDocumentRepository<Order, Key> {
    Flux<Order> findByCustomerIdOrderByOrderIdDesc(String customerId);    // read page by page as asked
    Mono<Order> findFirstByCustomerIdOrderByOrderIdDesc(String customerId);
    Mono<Boolean> existsByStatus(Status status);
}
```

**Kotlin coroutines.** With `kotlinx-coroutines-reactor` on the classpath, as well as the `kotlin-reflect` every Spring
Kotlin application has, a `@HalyxListener` may be a `suspend fun`. Returning acks the message (or commits the
record) and throwing retries it, as with any listener, wherever the coroutine resumes.

```kotlin
@HalyxListener("billing")
suspend fun onOrder(placed: CloudEvent) {
    invoices.put(BlobKey.of(placed.subject().orElseThrow()), render(placed)).awaitSingle()
}
```

A coroutine repository is a reactive one. Extend Spring Data's `Repository` and declare the CRUD methods you use as
`suspend` functions, with derived queries returning `Flow` for many results, or as `suspend` functions for one
result, an `existsBy...` answer or a `Window`. A missing entity is `null`. Entities are records, so a Kotlin entity
is a `@JvmRecord data class`. Spring Data's `CoroutineCrudRepository` doesn't work, because it declares `findAll`,
`count` and `deleteAll()`, which would scan the table. Mixing styles works too: an interface extending
`ReactiveDocumentRepository` may add `Flow` and `suspend` queries.

```kotlin
@JvmRecord
@DocumentTable("orders")
data class Order(@PartitionKey val customerId: String, @SortKey val orderId: String, val status: String)

interface Orders : Repository<Order, Key> {
    suspend fun save(order: Order): Order
    suspend fun findById(id: Key): Order?
    suspend fun deleteById(id: Key)
    fun findByCustomerIdOrderByOrderIdDesc(customerId: String): Flow<Order>      // read page by page as collected
    suspend fun findFirstByCustomerIdOrderByOrderIdDesc(customerId: String): Order?
    suspend fun findByCustomerId(customerId: String, position: ScrollPosition, limit: Limit): Window<Order>
}
```

Both are tested on Boot 3.5 (Kotlin 1.9) and Boot 4 (Kotlin 2.3), in memory and on Kafka, SQS and DynamoDB
emulators. For the reactive beans themselves, see [Kotlin coroutines](api/reactive.md#kotlin-coroutines).

**Tracing.** With Micrometer's `context-propagation` library on the classpath (Micrometer Tracing brings
it), Halyx's observations join the request's: WebFlux keeps the request's observation in the Reactor
context, and each reactive call restores it. **Caching**: `@Cacheable` methods returning `Mono` or `Flux` work
with Halyx caches (see [Spring's `@Cacheable`](#springs-cacheable)). **Health**: Halyx's health contributor
reports in reactive applications as in servlet ones.

## Observability

| Feature | Details |
|---|---|
| Observations | With Micrometer, every operation records a `halyx.operation` observation (a timer and a span) tagged with `halyx.service`, `halyx.operation`, `halyx.provider` and `error`. Turn off with `halyx.observations.enabled=false`. |
| Health | A `halyx` health contributor with one indicator per blob store (lists one item), cache (runs an authenticated command, even when fail-open), parameter store (an authenticated read, past its cache), document store and stream producer (reads the topic's partitions), plus the `messaging` consumers, the `streams` listeners and the `cache-manager` (an authenticated command, even when fail-open). Turn off with `management.health.halyx.enabled=false`. |
| Cache metrics | Micrometer's standard `cache.gets{result=hit\|miss}`, `cache.puts` and `cache.evictions`, plus `cache.loads{result}` and `cache.fail.open`, tagged `cache=<name>`, for `Cache` beans and `CacheManager` caches. |
| Messaging metrics | `halyx.messaging.messages{subscription, listener, result}`, `halyx.messaging.in.flight` and `halyx.messaging.receive.errors`. |
| Stream metrics | `halyx.stream.records{consumer, listener, result=received\|handled\|failed\|dead-lettered}`, `halyx.stream.poll.errors`, `halyx.stream.in.flight` and `halyx.stream.assigned.partitions`. |
| Parameter metrics | `halyx.parameters.reads{store, source=cache\|backend}`, `halyx.parameters.changes{store}` and `halyx.parameters.watch.failures{store}`. |
| Tracing | With Micrometer Tracing, publishers, event publishers and stream producers add the current trace context to message attributes, CloudEvent extensions (`traceparent`, `tracestate`) and record headers, and each listener invocation runs in a consumer span that continues the trace. Without Spring, `halyx-micrometer` does the same: see [Observations and tracing](api/errors.md#observations-and-tracing). |

## Customising

| Extension | Use it to |
|---|---|
| your own `Halyx` bean | replace the auto-configured one entirely |
| `HalyxBuilderCustomizer` beans | add plugins, interceptors or registrations to the builder; `halyx.*` resources can use the providers they register |
| `Interceptor` beans | wrap every operation; applied in `@Order` order. Order, outermost first: observations, your interceptors, retries |
| `MessageListenerDecorator` beans | wrap every `@HalyxListener` |
| `halyx.retry.*` | uniform retries (`enabled`, `max-attempts`, `initial-backoff`, `max-backoff`, `multiplier`); off by default, since the SDKs already retry |

Bean names are shared across blob stores, caches, topics, subscriptions, event buses, stream producers and
consumers, document stores, parameter stores, databases and your own beans, so keep them distinct. A key taken
twice fails at startup, naming both properties. Keys only default the cloud resource's name, so a renamed key can
keep it:

```yaml
halyx:
  topics:
    orders-publisher:
      name: orders                 # SNS topic, Service Bus topic or Pub/Sub topic "orders"
  subscriptions:
    orders-subscription:
      name: orders
```

## Fail-fast validation

Startup fails with a message naming the property for:
- an unknown provider type, with the module to add
- an unknown or ambiguous `provider` reference
- incomplete static credentials, impersonation without a target, a service principal without exactly one of
  `secret`, `certificate` and `token-file`, or a password login without its password
- a `json` cache without `trusted-packages`
- `halyx.r2dbc-databases` without `halyx-r2dbc`, or `halyx.spring-kafka.cluster` without Spring for Apache Kafka
- `halyx.databases` or `halyx.spring-kafka.cluster` on the memory provider, which has no SQL and whose streams
  Spring for Apache Kafka can't use: a profile without a cloud uses Boot's `spring.datasource` and
  `spring.kafka.bootstrap-servers` instead
- a `@HalyxListener` naming no subscription or stream consumer, a second listener on one stream consumer, or
  `concurrency` on a stream consumer's listener
- a bean named `messaging`, `streams` or `cache-manager`, which would hide a health entry of the same name
- a `halyx.*` key that is already a bean name: another entry's, in any group, or one of your beans

Spring Boot settings that a Halyx bean makes ineffective are logged as warnings at startup: `spring.datasource.*`
next to `halyx.databases`, `spring.r2dbc.*` next to `halyx.r2dbc-databases`, and `spring.cache.*` while
`halyx.cache-manager` provides the `CacheManager`. When your own `CacheManager` bean makes Halyx' back off,
`halyx.cache-manager.endpoint` is reported as ignored.

Configuration metadata is generated, so IDEs autocomplete `halyx.*` keys.

## Spring Boot 3.5 and 4

The same starter works on both: your application's Boot parent or BOM picks the Spring versions.
- **Compiled against:** the auto-configuration is compiled against Boot 3.5.
- **Health:** wired with Actuator's `org.springframework.boot.actuate.health` on Boot 3, and with the
  `org.springframework.boot.health` module on Boot 4. Exactly one of the two activates.
- **Tested on both:** the test suite runs on 3.5, and the same shared tests run again on 4.1.
- **Dependency versions:** your Boot BOM also picks Spring Framework, Reactor, Micrometer, Spring for Apache
  Kafka, Netty and Jackson, so their security fixes arrive with your Boot upgrades. Some fixes exist only in the
  lines Boot 4 manages (Spring Framework 7, Reactor 3.8, Micrometer 1.16, Spring for Apache Kafka 4), not in the
  Boot 3.5 lines. Without a Boot BOM, the provider modules bring patched versions of the libraries the cloud
  SDKs and clients pull in, such as Netty, Jackson and Kafka's compression libraries.
