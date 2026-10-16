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
      # credentials: { type: static, id: ..., secret: ... }   # default | static | anonymous
      properties:                  # provider settings: see Provider configuration
        http.max-connections: 256
```

Each entry is a named provider, like `Halyx.builder().register(name, config)`. When only one provider is
configured, resources use it without saying so; with several, each resource names its `provider`. See
[Provider configuration](providers.md) for every property.

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
`halyx.document-repositories.enabled=false`. Both work with the Spring Data each Boot version manages (2025.0 on Boot 3.5,
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
partition, until `max-attempts` sends it to the `dead-letter` producer. Stream consumers leave their group when
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
```

### Spring's `@Cacheable`

`halyx.cache-manager.*` backs Spring's caching annotations, `@Cacheable`, `@CachePut` and `@CacheEvict`
(with `@EnableCaching`):

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

## Observability

| Feature | Details |
|---|---|
| Observations | With Micrometer, every operation records a `halyx.operation` observation (a timer and a span) tagged with `halyx.service`, `halyx.operation`, `halyx.provider` and `error`. Turn off with `halyx.observations.enabled=false`. |
| Health | A `halyx` health contributor with one indicator per blob store (lists one item), cache (runs an authenticated command), parameter store (an authenticated read), document store and stream producer (reads the topic's partitions), plus the `messaging` consumers and `streams` listeners. Turn off with `management.health.halyx.enabled=false`. |
| Cache metrics | Micrometer's standard `cache.gets{result=hit\|miss}`, `cache.puts` and `cache.evictions`, plus `cache.loads{result}` and `cache.fail.open`, tagged `cache=<name>`, for `Cache` beans and `CacheManager` caches. |
| Messaging metrics | `halyx.messaging.messages{subscription, listener, result}`, `halyx.messaging.in.flight` and `halyx.messaging.receive.errors`. |
| Stream metrics | `halyx.stream.records{consumer, listener, result=received\|handled\|failed\|dead-lettered}`, `halyx.stream.poll.errors`, `halyx.stream.in.flight` and `halyx.stream.assigned.partitions`. |
| Parameter metrics | `halyx.parameters.reads{store, source=cache\|backend}`, `halyx.parameters.changes{store}` and `halyx.parameters.watch.failures{store}`. |
| Tracing | With Micrometer Tracing, publishers and stream producers add the current trace context to message attributes and record headers, and each listener invocation runs in a consumer span that continues the trace. |

## Customising

| Extension | Use it to |
|---|---|
| your own `Halyx` bean | replace the auto-configured one entirely |
| `HalyxBuilderCustomizer` beans | add plugins, interceptors or registrations to the builder |
| `Interceptor` beans | wrap every operation; applied in `@Order` order. Order, outermost first: observations, your interceptors, retries |
| `MessageListenerDecorator` beans | wrap every `@HalyxListener` |
| `halyx.retry.*` | uniform retries (`enabled`, `max-attempts`, `initial-backoff`, `max-backoff`, `multiplier`); off by default, since the SDKs already retry |

Bean names are shared across blob stores, caches, topics, subscriptions, document stores, parameter stores,
databases and your own beans, so keep them distinct.

## Fail-fast validation

Startup fails with a message naming the property for:
- an unknown provider type, with the module to add
- an unknown or ambiguous `provider` reference
- incomplete static credentials

Configuration metadata is generated, so IDEs autocomplete `halyx.*` keys.

## Spring Boot 3.5 and 4

The same starter works on both: your application's Boot parent or BOM picks the Spring versions.
- **Compiled against:** the auto-configuration is compiled against Boot 3.5.
- **Health:** wired with Actuator's `org.springframework.boot.actuate.health` on Boot 3, and with the
  `org.springframework.boot.health` module on Boot 4. Exactly one of the two activates.
- **Tested on both:** the test suite runs on 3.5, and the same shared tests run again on 4.1.
