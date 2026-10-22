---
title: API guide
nav_order: 3
has_children: true
permalink: /api/
---

# API guide
{: .no_toc }

Every Halyx service follows the same shape: you register providers once, ask a provider for a service,
and use that service's interface whichever cloud is behind it.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## The object model

```
Halyx                                   one per application
 ├── provider("orders")  → CloudContext   AWS, eu-west-2
 │     ├── blobStore("acme-invoices")      → BlobStore        (Amazon S3)
 │     ├── documentStore(TableConfig)       → DocumentStore    (DynamoDB)
 │     ├── publisher("orders")              → Publisher        (SNS)
 │     ├── subscription("orders-billing")   → Subscription     (SQS)
 │     ├── eventPublisher("orders")         → EventPublisher   (EventBridge)
 │     ├── streamProducer(StreamProducerConfig) → StreamProducer (MSK)
 │     ├── streamConsumer(StreamConsumerConfig) → StreamConsumer (MSK)
 │     ├── database(SqlConfig)              → Database         (RDS)
 │     ├── cache(CacheConfig)               → Cache<V>         (ElastiCache)
 │     └── parameterStore(ParameterStoreConfig) → ParameterStore (Secrets Manager, SSM)
 └── provider("analytics") → CloudContext  Google Cloud, acme-prod
       └── ...
```

| Type | Role |
|---|---|
| `Halyx` | The registry of named providers. Build it once, share it, and close it on shutdown. |
| `ProviderConfig` | Which cloud (`aws`, `azure`, `gcp`, `vault`, `memory`), plus region, endpoint, credentials and provider properties. See [Provider configuration](../providers.md). |
| `CloudContext` | One provider connection: an account and region with its credentials. It hands out services, which share its HTTP clients and connection pools. Asking again with an equal configuration returns the same service (with its caches and statistics), except stream consumers and databases, which are new each time. |
| Service interfaces | `BlobStore`, `DocumentStore`, `Publisher` and `Subscription`, `EventPublisher`, `StreamProducer` and `StreamConsumer`, `Database`, `Cache<V>`, `ParameterStore`. |

```java
Halyx halyx = Halyx.builder()
        .register("orders", ProviderConfig.of("aws").region("eu-west-2"))
        .register("analytics", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, "acme-prod"))
        .interceptor(new RetryInterceptor(RetryPolicy.defaults()))   // optional, applies to every service
        .build();

CloudContext orders = halyx.provider("orders");
halyx.names();   // [orders, analytics]
```

## Services

| Page | Interface | What it covers |
|---|---|---|
| [Object storage](storage.md) | `BlobStore` | put, get, streams, parallel transfers, listing, conditional writes, checksums, tiers, presigned URLs |
| [NoSQL documents](nosql.md) | `DocumentStore` | items by partition and sort key, partial updates, queries, secondary indexes, transactions, expiry |
| [Messaging](messaging.md) | `Publisher`, `Subscription` | publishing, listeners, manual receive, ordering, deduplication, delays, claim checks |
| [Events](events.md) | `CloudEvent`, `EventPublisher` | CloudEvents to EventBridge, Event Grid and Eventarc Advanced, and decoding what their rules deliver |
| [Streams](streams.md) | `StreamProducer`, `StreamConsumer` | Kafka topics on MSK, Event Hubs and Managed Kafka: keys and order, consumer groups, listeners, commits, dead letters, transactions |
| [SQL databases](sql.md) | `Database` | a pooled JDBC `DataSource` with IAM, Entra ID or password login |
| [Caches](caches.md) | `Cache<V>` | Redis and Valkey operations, atomic updates, counters, locks, fail-open |
| [Parameters and secrets](parameters.md) | `ParameterStore` | configuration values and secrets, versions, JSON fields, watching for changes |
| [Errors and retries](errors.md) | `CloudException` | the portable error model, retries, throttling and interceptors |
| [Capabilities and native clients](capabilities-and-unwrap.md) | `Capabilities`, `unwrap` | optional features per provider, and the native SDK clients |
| [Reactive and async code](reactive.md) | `ReactiveHalyx` and a reactive view of each | `halyx-reactor`: `Mono` and `Flux` for every service on a scheduler you choose, streams in and out, subscriptions as a `Flux`, interoperability, testing |

## Conventions shared by every service

**Builders and immutable values.** Options and configs are immutable and built fluently:
`PutOptions.builder()...build()`, `TableConfig.builder("orders").sortKey("sk").build()`. Keys and other
small values are records, such as `BlobKey`, `Key`, `ETag` and `Version`.

**Synchronous, thread-safe, virtual-thread friendly.** Every call blocks until it completes, and every
service object is thread-safe. Calls are cheap to make from virtual threads, and Halyx itself uses
virtual threads for parallel transfers and message listeners. Reactive and async applications wrap the
calls on threads they choose, with `halyx-reactor`'s `Mono` and `Flux` views; see
[Reactive and async code](reactive.md).

**One error model.** Failures are unchecked `CloudException` subtypes, such as `NotFoundException`,
`PreconditionFailedException` and `ThrottledException`, with the same meaning on every provider. See
[Errors and retries](errors.md).

**Capabilities instead of silent differences.** Features not every provider has are declared, and
asking for a missing one fails rather than being ignored. See
[Capabilities and native clients](capabilities-and-unwrap.md).

**Infrastructure stays yours.** Halyx uses buckets, tables, topics, queues, caches and databases; it
doesn't create them. Create them with Terraform, Bicep, CloudFormation or your usual tooling.

## Lifecycle

`Halyx` implements `AutoCloseable`. Closing it first stops the listeners and stream consumers started from
it, letting running handlers finish and commit or ack, then closes every provider connection, SDK client,
cache connection and database pool it opened.

Create each service once and keep it, typically in a field or as an injected bean, rather than fetching
it per request:

- `blobStore(bucket)` returns the same instance for the same bucket.
- `database(config)` opens a **new connection pool** on every call.
- Each `ParameterStore` has its own read cache (`cacheTtl`), so separate instances don't share cached
  values.
- Publishers and subscriptions may hold their own connections, for example Service Bus senders and
  receivers.
- Caches share one connection per endpoint and credentials, whichever instance you use.

In Spring Boot, the starter builds the `Halyx` bean and one bean per configured service, and closes
them with the application context. See [Spring Boot](../spring-boot.md).
