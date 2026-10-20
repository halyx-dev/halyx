---
title: Design, build and test
nav_order: 9
---

# Design, build, test and benchmarks
{: .no_toc }

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Design

### Architecture

Every service is split into a portable **API** that applications use, and an **SPI** that each cloud
implements. The API does validation, capability checks, interceptors and error translation once, for
every provider:

```
 application ──► BlobStore (API)                                        halyx-core
                   │ DefaultBlobStore: validation → capability check →
                   │ interceptor chain → driver call → error translation
                   ▼
                 BlobStoreDriver (SPI)
       ┌───────────┼──────────────────┬──────────────────┐
  S3BlobStoreDriver  AzureBlobStoreDriver  GcsBlobStoreDriver  InMemoryBlobStoreDriver
   halyx-aws          halyx-azure          halyx-gcp          halyx-core
```

The same split repeats for every service: `DocumentStore` and `DocumentStoreDriver`, `Publisher` and
`PublisherDriver`, `StreamProducer` and `ProducerDriver`, `Cache` and `CacheDriver`, `ParameterStore` and
`ParameterDriver`. Providers are
discovered with `ServiceLoader`, so `halyx-core` has no cloud SDK dependency, and the build enforces
that.

### Patterns

| Pattern | Where |
|---|---|
| Bridge | each API (`BlobStore`, `DefaultBlobStore`) and its SPI (`BlobStoreDriver`) |
| Plugin, Separated Interface | `CloudProvider` in core, discovered from provider jars through `ServiceLoader` |
| Abstract Factory | `ProviderConnection` creates the family of drivers for one account and region |
| Registry, Facade | `Halyx` holds the named `CloudContext`s |
| Gateway, Adapter | each driver wraps a native SDK client |
| Template Method | an `Abstract*Driver` base per SPI, which every driver extends: blob stores, document stores, publishers and subscriptions, event publishers, stream producers and consumers, caches and parameter stores (translation fallback, `unwrap`) |
| Chain of Responsibility | `Interceptor` and `InterceptorChain` (`RetryInterceptor` included) |
| Value Object | `BlobKey`, `ETag`, `Key`, `Version`, `ProviderConfig`: immutable |
| Service Stub | the `memory` provider, for tests |
| Exception Translation | the sealed `CloudException` hierarchy with `isRetryable()` |

### Principles

- **Portable semantics, not just portable signatures.** Listing order, idempotent delete, conditional
  writes, versions and expiry mean the same on every provider, and the conformance kits check it.
- **Declare differences instead of hiding them.** Optional features are capabilities. Asking for a
  missing one fails rather than being ignored, and `unwrap` reaches the native client.
- **No hidden waits.** Halyx doesn't block your call to paper over eventual consistency or
  asynchronous deletes. It fails fast with a retryable error, and you choose how to retry.
- **Infrastructure is yours.** Halyx never creates buckets, tables, topics or databases.
- **Fit the platform.** Java 21 virtual threads for parallel transfers and listeners, pooled buffers for
  part uploads, shared HTTP clients per provider connection, and SDK-native retry modes.

### Modules

| Module | Purpose |
|---|---|
| `halyx-bom` | Import to align Halyx versions |
| `halyx-core` | portable API, provider SPI, error model, interceptors, in-memory provider. No cloud SDKs (enforced) |
| `halyx-tck` | the conformance test kits every provider must pass |
| `halyx-redis` | the shared Redis and Valkey cache driver (Lettuce), used by every provider |
| `halyx-sql` | the shared HikariCP pool with per-connection credentials (passwords, cloud IAM tokens) |
| `halyx-r2dbc` | pooled R2DBC connections with the same password and cloud IAM logins, for reactive code and Spring Data R2DBC |
| `halyx-reactor` | Project Reactor views of every service on the application's scheduler: `Mono` and `Flux`, streams in and out, subscriptions and stream consumers as a `Flux` of deliveries |
| `halyx-micrometer` | Micrometer observations of every operation, and trace context through messages, events and stream records, for any application; the starter applies them |
| `halyx-kafka` | the shared Kafka stream driver, with password and cloud IAM login (SASL `OAUTHBEARER`), used by every provider |
| `halyx-aws` | AWS SDK v2: S3, DynamoDB, SNS, SQS, EventBridge, SSM Parameter Store, Secrets Manager, ElastiCache and RDS IAM login |
| `halyx-azure` | Azure SDK: Blob Storage, Cosmos DB, Service Bus, Event Grid namespaces, Key Vault, App Configuration, Entra ID login for caches and databases |
| `halyx-gcp` | Google Cloud: Cloud Storage, Firestore, Pub/Sub, Eventarc Advanced, Secret Manager, Parameter Manager, Memorystore and Cloud SQL IAM login |
| `halyx-vault` | HashiCorp Vault KV v2 parameters and secrets |
| `halyx-spring-data-document` | Spring Data repositories over document stores: records as entities, derived queries on keys and indexes, optimistic locking, keyset scrolling |
| `halyx-spring-boot-autoconfigure` | Spring Boot auto-configuration, properties, health and observations |
| `halyx-spring-boot-starter` | the dependency Spring Boot applications add |
| `halyx-reactor-tests` | the conformance kits through `halyx-reactor`'s adapters, on the memory provider and on Kafka and Valkey (not published) |
| `halyx-spring-boot4-tests` | the shared Spring and Spring Data tests run on Boot 4 (not published) |
| `halyx-benchmarks` | JMH benchmarks and a load-test harness (not published) |
| `halyx-samples` | `portable-orders`, a Spring Boot 4 service on every Halyx API, and `portable-orders-boot3`, the same on Boot 3.5, with their end-to-end tests (not published); see [Portable microservice](portable-service.md) |

### AI skills

The skills for AI coding assistants live in `.claude/skills/`, outside the Maven reactor, and are named
`halyx-ai-<verb>` so they can't be mistaken for modules. See [AI skills](ai-skills.md).

## Build

```bash
mvn verify          # unit tests and every conformance kit against emulators (needs Docker)
mvn test            # unit tests and the kits on the in-memory provider only
mvn -pl halyx-aws -am verify -Dit.test='S3BlobStoreConformanceIT'   # one suite
```

Requirements: Java 21 or later, Maven 3.9 or later, and Docker for the integration tests. They're skipped
automatically when Docker is unavailable. `docker compose -f cloud-testing/docker-compose.yml up -d` starts the
emulators for manual experiments.

Every source file carries the Apache-2.0 license header, and the build checks it. Run
`mvn -N com.mycila:license-maven-plugin:format` to add any missing ones.

## Testing

### Conformance kits

`halyx-tck` holds one conformance kit per service. Every provider runs the same kit, so a behaviour is
either portable or explicitly declared as a capability:

| Kit | Tests | What it covers |
|---|---|---|
| Blob store | 80 | round trips, the asynchronous view, streams, parallel transfers, listing and pagination, conditional writes and deletes, batch delete, copy, metadata, checksums, tiers, presigned URLs and multipart |
| Document repositories | 10 | Spring Data repositories on the document store kit's tables: CRUD with records, `UUID` keys, optimistic locking and taken ids, batched saves, partition queries both ways with sort-key conditions and limits, windows, streams, index queries with and without a sort attribute |
| Reactive document repositories | 10 | the document repository cases through `ReactiveDocumentRepository`: `Mono` and `Flux` results, saves from a `Publisher`, `Mono<Window>` scrolling, and `Flux` queries that stop early |
| Document store | 26 | the asynchronous view, every value type, versions and conditions, partial updates with concurrent increments, batches beyond provider limits, sort-key queries both ways with paging, secondary indexes, transactions with rollback, keys with `/ ? # % \`, expiry |
| Event bus | 9 | asynchronous publishes, every CloudEvent attribute through the router to its queue, text and binary data, events without data, batches across requests, republished ids, oversized events refused, listeners |
| Messaging | 21 | round trips, asynchronous publishes and receives, binary bodies, ack and nack, lease expiry and extension, batches, listener retries and concurrency, claim checks, ordering, deduplication, delays |
| Streams | 28 | asynchronous sends and polls, keys, headers, partitions and offsets, order per key, committed positions, uncommitted records read again, groups sharing partitions, start positions, listener retries holding only their partition, dead letters, commits on close, claim checks, idempotence and transactions |
| Cache | 52 | ping, the asynchronous view, basic and bulk operations, cache-aside, atomic operations, counters, locks, namespaces, expiry (fail-open has its own test against an unreachable server) |
| Parameter store | 26 | CRUD, the asynchronous view, ping past the cache, versions, prefixes, caching, JSON fields, native names, watching |

### Emulators

Integration tests (`*IT`) run the kits through Testcontainers against
[Floci](https://floci.io) (`floci/floci` for AWS, `floci/floci-az` for Azure, `floci/floci-gcp` for
Google Cloud), Google's Pub/Sub and Firestore emulators, Microsoft's Service Bus emulator, Valkey (plain,
TLS with a private CA, and a 3-master cluster), HashiCorp Vault, PostgreSQL, MySQL and Apache Kafka. Image
versions are pinned in the parent POM. `halyx-kafka` runs its broker tests twice, on Kafka 3.9 and on Kafka 4.
The event bus kit runs on Floci's EventBridge; Event Grid namespaces and Eventarc Advanced have no emulator, so
their kits run on the real clouds only.

Behaviour an emulator doesn't implement is declared per provider in `knownEnvironmentGaps()`, each case
confirmed with raw HTTP requests against the emulator. Those tests are reported as skipped with the
reason, so the kits stay strict for real clouds:

| Emulator | Gap | Test skipped |
|---|---|---|
| Floci (AWS) | drops `Content-Language` | `putStoresStandardHttpHeaders` |
| Floci-az | ignores `If-None-Match` on Put Block List | `writeInPartsWithIfNotExistsRejectsExistingBlob` |
| Floci-az | no Set Blob Tier, Set Blob Properties or Blob Batch; no `x-ms-access-tier` | tier and metadata-update tests |
| Floci-gcp | drops `cacheControl` on copy; metadata PATCH replaces the map | `copyPreservesHttpHeaders`, `updateMetadataReplacesHeadersAndMetadataButNotContent` |
| Floci-gcp | no redelivery on Pub/Sub; increments not atomic in Firestore | the redelivery and increment tests (covered by Google's emulators) |

### Real clouds

The same kits run against real AWS, Google Cloud and Azure, on throwaway infrastructure that's always torn
down. They found bugs the emulators hid, from unprocessed DynamoDB batches to Cosmos DB id escaping and
Pub/Sub ordering keys. See [Cloud testing](cloud-testing/index.md) for how the runs work and every result.

### Spring

The Spring tests cover beans from properties (with providers from customizers and cache codecs), `@HalyxListener`
with retries and startup checks, claim checks, configuration import with live reload, health (including fail-open
caches), metrics and tracing through messages, events and records. They run on Spring Boot 3.5, and the same shared
tests run again on Boot 4.1. On the real clouds, Spring Boot 3 and 4 also run repositories, events, reactive beans,
`@HalyxListener` with claim checks and configuration import in tier 1, R2DBC in tier 2, caches and `@Cacheable`
in tier 3, and streams in tier 4.

## Benchmarks

```bash
mvn -pl halyx-benchmarks -am package -DskipTests
java -jar halyx-benchmarks/target/benchmarks.jar                      # all JMH benchmarks
java -jar halyx-benchmarks/target/benchmarks.jar TransferBenchmark -p latencyMillis=50
```

The JMH benchmarks measure Halyx itself, so they need no cloud account. `TransferBenchmark` and
`BulkBenchmark` run against a simulated network: every request pays a round-trip latency, and each
connection is capped at a bandwidth. That shows what the parallel engines gain, independently of your
network. One short run on a laptop, at 20 ms latency and 100 MB/s per connection:

| Benchmark | Sequential | Halyx | Speed-up |
|---|---|---|---|
| 64 MiB download, 8 MiB ranges | 1,004 ms | 310 ms (parallelism 4), 219 ms (8) | 3.2x, 4.6x |
| 64 MiB upload, 8 MiB parts | 1,125 ms | 445 ms (concurrency 4), 228 ms (8) | 2.5x, 4.9x |
| delete 500 keys, no batch API | 10,934 ms | 705 ms (16 at a time) | 15x |
| list 5,000 keys in 5 pages, 20 µs of work per key | 291 ms | 194 ms (next page prefetched) | 1.5x |
| checksum one 8 MiB part | MD5: 660 MiB/s | CRC32C: about 16 GiB/s | about 25x |

Calling the portable API costs about a microsecond more per call than calling the driver directly
(`CoreBenchmark`), which is noise next to any network round trip. These runs were short, with wide error
bars; rerun them on your own hardware before relying on the figures.

### Load testing a real bucket

Emulators don't behave like cloud storage under load, so tune against a real bucket with `LoadTest`. It
uses each cloud's default credentials and deletes its objects afterwards:

```bash
java -cp halyx-benchmarks/target/benchmarks.jar \
  -Dprovider=aws -Dbucket=my-bench-bucket -Dregion=eu-west-1 \
  -Dconcurrency=64 -Dduration=60s -Dsize=1MiB -Dmix=put:20,get:70,head:10 \
  -Dhalyx.http.max-connections=256 \
  dev.halyx.benchmarks.LoadTest
```

It prints count, operations per second, MB/s, p50, p90, p99 and maximum latency, and errors, for each
operation. Any `halyx.*` property goes to the provider, for example `-Dhalyx.aws.retry-mode=standard`.
Use `-Dparallelism=8` for parallel ranged gets, and `-Dendpoint=...` for S3-compatible stores.
