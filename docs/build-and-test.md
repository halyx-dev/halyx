---
title: Build and test
nav_order: 10
---

# Build, test and benchmarks
{: .no_toc }

How to build Halyx, how it's tested against emulators and the real clouds, and how it's benchmarked. See
[Architecture and design](design.md) for how it's put together.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

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

The skills for AI coding assistants live in `.claude/skills/`, outside the Maven reactor, and are named
`halyx-ai-<verb>` so they can't be mistaken for modules. See [AI skills](ai-skills.md).

`mvn verify` also compares each published module with the last release, `japicmp.baseline` (1.1.0), from Maven
Central, using [japicmp](https://siom79.github.io/japicmp/). A change that would break code compiled against that
release, or its source, fails the build: a removed or changed public method, constructor or field, a method added to an
interface without a default, a narrower visibility. Its report is in each module's `target/japicmp/`. Test modules,
samples and benchmarks aren't published and set `japicmp.skip`. The check covers the provider SPI too, since other
providers implement it, which is why its additions come with defaults. Keep the old signature next to a new one: an
overload, a constructor that delegates, a default method. The baseline moves to each new release.

## Testing

### Conformance kits

`halyx-tck` holds one conformance kit per service. Every provider runs the same kit, so a behaviour is
either portable or explicitly declared as a capability:

| Kit | Tests | What it covers |
|---|---|---|
| Blob store | 80 | round trips, the asynchronous view, streams, parallel transfers, listing and pagination, conditional writes and deletes, batch delete, copy, metadata, checksums, tiers, presigned URLs and multipart |
| Blob encryption | 16 | what an encrypted view adds, beyond the blob kit run through it: sealed data and its marker in the store, `Content-Encoding` kept off the ciphertext, plaintext sizes with stored sizes in listings, reserved metadata names and metadata updates, a blob moved to another key or changed or truncated, plain blobs refused, a key from another store, ranges across segments and parallel reads, prefix views, a key per blob, one wrap per write and one unwrap per read, copies in and out of the encrypted scope, and the asynchronous view. Runs with memory keys on every provider, and with each provider's own key service |
| Document repositories | 11 | Spring Data repositories on the document store kit's tables: CRUD with records, `UUID` keys, optimistic locking and taken ids, batched saves, partition queries both ways with sort-key conditions and limits, windows, streams, index queries with and without a sort attribute, `@Encrypted` components sealed in the table |
| Reactive document repositories | 11 | the document repository cases through `ReactiveDocumentRepository`: `Mono` and `Flux` results, saves from a `Publisher`, `Mono<Window>` scrolling, and `Flux` queries that stop early |
| Document encryption | 14 | what encrypted fields add, beyond the document kit run through them: values sealed at rest and read back with their types, nulls and empty values, one wrap per document write and one unwrap per document read, a query page through the blocking and asynchronous views, values moved to another item or field or changed, values in the clear refused, items from before encryption, updates, increments and key or index attributes refused, a key per item, any key of the store opening a field, batches and transactions, and no calls for a table without encrypted fields. Runs with memory keys on every provider, and with each provider's own key service |
| Document store | 26 | the asynchronous view, every value type, versions and conditions, partial updates with concurrent increments, batches beyond provider limits, sort-key queries both ways with paging, secondary indexes, transactions with rollback, keys with `/ ? # % \`, expiry |
| Event bus | 9 | asynchronous publishes, every CloudEvent attribute through the router to its queue, text and binary data, events without data, batches across requests, republished ids, oversized events refused, listeners |
| Functions | 12 | one test function per environment: the payload round trip, configured and per-request headers reaching the function, a function that throws, 400 and 404, a missing function, payload and header limits, timeouts, a failed or timed-out non-idempotent invocation running once, the asynchronous view, and `trigger` where declared |
| Messaging | 21 | round trips, asynchronous publishes and receives, binary bodies, ack and nack, lease expiry and extension, batches, listener retries and concurrency, claim checks, ordering, deduplication, delays |
| Streams | 28 | asynchronous sends and polls, keys, headers, partitions and offsets, order per key, committed positions, uncommitted records read again, groups sharing partitions, start positions, listener retries holding only their partition, dead letters, commits on close, claim checks, idempotence and transactions |
| Cache | 52 | ping, the asynchronous view, basic and bulk operations, cache-aside, atomic operations, counters, locks, namespaces, expiry (fail-open has its own test against an unreachable server) |
| Parameter store | 26 | CRUD, the asynchronous view, ping past the cache, versions, prefixes, caching, JSON fields, native names, watching |
| Parameter encryption | 12 | what an encrypted view adds, beyond the parameter kit run through it: HLXS text in the store and the plaintext from reads and puts, special characters and JSON fields, the binding to the full name across prefixes, a changed value, a key from another store, values in the clear refused unless allowed and a store moved to encryption one value at a time, a key per name, one wrap per put and no unwrap for a cached read, listings and the asynchronous view, and sealed values too long for the backend refused before they are sent. Runs with memory keys on every provider, and with each provider's own key service where its emulator has one |
| Key store | 63 | round trips from empty to many segments, with and without AAD; every header field, key entry and segment changed, reordered, truncated or extended; wrong keys and keys outside the store; data sealed for several keys and for 16 at most, keys added and removed with the body unchanged, unknown entries skipped; the key commitment; data keys and streams; rewrap after rotation, across keys and across stores; signing per algorithm, checked by the JDK alone, and after rotation; purposes, missing and disabled keys; data key reuse and the decrypt cache; names, metadata, ping, MACs and the asynchronous view. Several-key cases seal next to a memory key store the kit starts itself |

The kits for services that can use keys run unchanged through encryption, rather than through parallel kits. Each
has a hook that defaults to doing nothing: `wrap(...)` on the blob store, the parameter store and the claim-check
store of the messaging and stream kits, and `tables(...)` on the document kits' table configuration, which every
subclass's `store(sorted)` builds through. An encrypted subclass overrides the hook, so the whole kit runs again
through the encrypted view. A test that can't apply under encryption is named in `notApplicableWithEncryption()` with
its reason, and is reported as skipped, as an environment gap is; a test that fails through the view is a bug until
shown otherwise.

### Emulators

Integration tests (`*IT`) run the kits through Testcontainers against
[Floci](https://floci.io) (`floci/floci` for AWS, `floci/floci-az` for Azure, `floci/floci-gcp` for
Google Cloud), Google's Pub/Sub and Firestore emulators, Microsoft's Service Bus emulator, Valkey (plain,
TLS with a private CA, and a 3-master cluster), HashiCorp Vault, PostgreSQL, MySQL and Apache Kafka. Image
versions are pinned in the parent POM. `halyx-kafka` runs its broker tests twice, on Kafka 3.9 and on Kafka 4.
The event bus kit runs on Floci's EventBridge; Event Grid namespaces and Eventarc Advanced have no emulator, so
their kits run on the real clouds only. The function kit's test function, in Python, runs on Floci's Lambda (in a
Lambda runtime container, with the Docker socket), on the Azure Functions host container
(`mcr.microsoft.com/azure-functions/python`), and on Google's Functions Framework built from `python:3.12-slim`.

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
