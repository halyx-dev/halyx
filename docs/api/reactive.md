---
title: Reactive and async code
parent: API guide
nav_order: 11
---

# Reactive and async code: `halyx-reactor`
{: .no_toc }

`halyx-reactor` gives every Halyx service a [Project Reactor](https://projectreactor.io) view: `Mono` and `Flux`
over the blocking API, running on a scheduler the application chooses. Halyx creates no threads of its own for it.
Spring Boot applications get the reactive views as beans, reactive Spring Data repositories, and `@HalyxListener`
methods that return a `Mono`; see [Spring Boot](../spring-boot.md#reactive-applications).
[R2DBC](sql.md#reactive-r2dbc), in `halyx-r2dbc`, is reactive already.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

```xml
<dependency>
  <groupId>dev.halyx</groupId>
  <artifactId>halyx-reactor</artifactId>
</dependency>
```

It needs Reactor 3.7 or later (Spring Boot 3.5 and 4 manage 3.7 and 3.8). Most calls block underneath, on the
scheduler's threads: virtual threads make that cheap, but it isn't non-blocking I/O. Where a driver has a
non-blocking path, the reactive view uses it through the service's asynchronous view, and no thread waits: today
blob `put` of content in memory, `getBytes`, `head`, `exists`, `delete` and `list` on S3 and Azure Blob Storage
([asynchronous calls](storage.md#asynchronous-calls)); document `get`, `put`, `delete` and queries on DynamoDB,
Cosmos DB and Firestore ([asynchronous calls](nosql.md#asynchronous-calls)); message publishes on SNS, SQS,
Service Bus and Pub/Sub; and event publishes on EventBridge, Event Grid and Eventarc. Other calls, and Cloud
Storage, whose library has no asynchronous API, take the blocking path on the scheduler, with the same results.

## Getting the reactive services

`ReactiveHalyx` mirrors `Halyx`, and `provider(name)` mirrors `CloudContext`:

```java
Halyx halyx = Halyx.builder().register("orders", ProviderConfig.of("aws").region("eu-west-2")).build();

ReactiveHalyx rx = ReactiveHalyx.withVirtualThreads(halyx);      // one virtual thread per call
ReactiveBlobStore invoices = rx.provider("orders").blobStore("acme-invoices");
ReactiveDocumentStore orders = rx.provider("orders").documentStore(TableConfig.builder("orders").sortKey("sk").build());
```

Or wrap services you already have: `rx.wrap(blobStore)`, `rx.wrap(subscription)` and so on, for every service.
Each reactive service's `blocking()` returns the service it wraps.

**Choose the threads.** `ReactiveHalyx.withVirtualThreads(halyx)` makes a virtual-thread scheduler and disposes of
it on `close()`. To use your own:

```java
Scheduler calls = ReactiveHalyx.virtualThreadScheduler("halyx");   // or Schedulers.boundedElastic()
ReactiveHalyx rx = ReactiveHalyx.builder(halyx)
        .scheduler(calls)              // stays yours: close() leaves it alone
        .maxConcurrency(64)            // most calls in flight across every service; 0 (default) for no limit
        .build();
```

Use virtual threads or `Schedulers.boundedElastic()`, never `Schedulers.parallel()`: the scheduler's threads
block for the length of each call. A separate scheduler, or a concurrency limit, keeps a slow service from
holding up the rest.

## What it costs

Each call moves to the scheduler and back: about 5 µs on virtual threads and 10 µs on `boundedElastic`, against
a blocking call, measured with no network at all (`ReactiveBenchmark` in `halyx-benchmarks`). With 5 ms of
latency per call, one call or a hundred fanned out, the difference is within the noise, as it is for any real
cloud call. Fanning a hundred calls out through the adapters costs about the same as running them on a
virtual-thread executor.

## How calls behave

| | |
|---|---|
| When | when subscribed, once per subscription: `retry()` and `repeat()` call Halyx again |
| Where | on the scheduler; Halyx's own retries, interceptors and observations still apply inside the call |
| Results | a `Mono` per call; an empty `Optional` in the blocking API is an empty `Mono`; `void` is `Mono<Void>` |
| Errors | the blocking API's `CloudException`s, such as `NotFoundException` and `PreconditionFailedException` |
| Cancelling | a call that has started runs to completion and its result is dropped, so a cancelled write may still take effect; `timeout()` therefore doesn't free the call's thread, which `maxConcurrency` bounds |
| Context | with Micrometer's `context-propagation` on the classpath, each call sees the subscriber's context: the Reactor `Context`, then the subscribing thread's thread locals (MDC, the current observation, anything registered with `ContextRegistry`) |

```java
Mono<byte[]> invoice = invoices.getBytes(BlobKey.of("2026/inv-1.pdf"))
        .timeout(Duration.ofSeconds(5))
        .onErrorResume(NotFoundException.class, e -> Mono.empty());
```

## Reading

| Service | Single results | Streams |
|---|---|---|
| `ReactiveBlobStore` | `getBytes`, `head`, `exists`, `download`, `list`, presigning | `listAll(prefix)` page by page; `getBody(key)` as `Flux<ByteBuffer>` |
| `ReactiveDocumentStore` | `get`, `getAll`, `query`, `queryIndex` | `queryAll`, `queryIndexAll` page by page |
| `ReactiveCache<V>` | every operation; `getOrLoad(key, Mono<V> loader)` | |
| `ReactiveParameterStore` | `get`, `require`, `getAll` | `watch(prefix, interval)` as `Flux<ParameterChanges>` |

Listings and queries read pages as the subscriber asks for items, at most one page ahead of its demand, and
none after cancellation:

```java
Flux<Item> recent = orders.queryAll(Query.builder("c-42").descending().build()).take(20);   // reads one or two pages
```

`getBody` reads the blob in chunks (64 KiB by default) as they are requested, and closes it when the `Flux`
completes, fails or is cancelled:

```java
Flux<ByteBuffer> body = invoices.getBody(BlobKey.of("exports/orders.csv"));
```

## Writing

Every write is a `Mono`: `put`, `update`, `delete`, `publish`, `send`, `put` on caches and parameters.
Streams of items can go straight in, batched as they arrive:

```java
Flux<PublishResult> published = rx.provider("orders").publisher("orders")
        .publishAll(orderEvents.map(OutgoingMessage::of));            // batches of 100, flushed after 50 ms

Flux<Key> written = orders.putAll(rows.map(row -> Map.entry(row.key(), row.document())), 25, Duration.ofMillis(20));
```

`publishAll`, `sendAll` (stream producers), `publishAll` (event buses) and `putAll` (documents) group items into
batches of up to the given size, flush a partial batch after the wait, and send one batch at a time, in order, so
a slow send holds back the source. Each item comes out once its batch is written; a failed batch fails the
`Flux`, and earlier batches stay written.

Blob bodies upload from a `Publisher<ByteBuffer>` as it produces them, in parts for large bodies. The blob is
stored only if the body completes:

```java
Mono<PutResult> uploaded = invoices.put(BlobKey.of("exports/orders.csv"), csvChunks, PutOptions.defaults());
```

## Consuming messages, events and records

A subscription or stream consumer becomes a `Flux` of deliveries. Each subscriber starts its own consumer,
which stops when the subscriber goes away.

```java
ReactiveSubscription billing = rx.provider("orders").subscription("orders-billing");

billing.process(ConsumerOptions.DEFAULTS.withConcurrency(16), message -> bill(message))
       .subscribe();
```

- **`process(options, handler)`** runs the handler for each message, up to the consumer's concurrency at once.
  The handler's publisher completing acks the message, and failing nacks it for redelivery after the
  consumer's backoff. Its values are emitted once the message is acked, so everything emitted belongs to a
  message that won't come back.
- **`deliveries(options)`** hands over each `Delivery` to settle yourself: `ack()` or `nack(cause)`.
- **`events(options)`** and **`processEvents(...)`** decode [CloudEvents](events.md) from an event bus's queue;
  a message that holds none is nacked, so retries and dead-lettering apply.

The consumer waits until each delivery is settled, so no more than `concurrency` messages are in flight, and a
slow pipeline slows delivery rather than buffering messages whose leases run out. Leases are extended while
deliveries wait. Cancelling the `Flux` nacks what is still unsettled, for prompt redelivery.

Stream consumers work the same way, with `records(options)` and `process(options, handler)`: completing marks
a record handled, so its offset is committed, and failing retries it after the backoff, holding back the rest of
its partition until `maxAttempts` sends it to the dead letter. Records of a partition are handled one at a time,
in order. Cancelling keeps the member in its group, so a new subscriber carries on where it left off;
`close()` leaves the group and hands its partitions on.

For messages pulled by hand: `receive(max, wait)`, then `ack`, `nack` and `extendLease` as `Mono`s.

## Other blocking work

`call` and `stream` run any blocking code the way the services' calls run: on the scheduler, with the
subscriber's context and within the concurrency limit.

```java
Mono<Report> report = rx.call(() -> reports.render(id));                 // a JDBC query, a legacy client, ...
Flux<Row> rows = rx.stream(() -> jdbcTemplate.queryForStream(sql, mapper)); // pulled as asked, closed at the end
```

## Other reactive libraries

The reactive services' `Mono` and `Flux` are Reactive Streams publishers, and they take any publisher in.

```java
Single<byte[]> rxJava = Single.fromPublisher(invoices.getBytes(key));                      // RxJava 3
Uni<byte[]> mutiny = Uni.createFrom().publisher(JdkFlowAdapter.publisherToFlowPublisher(  // Mutiny 2 speaks Flow
        invoices.getBytes(key)));
Flow.Publisher<Item> flow = JdkFlowAdapter.publisherToFlowPublisher(orders.queryAll(query)); // the JDK's Flow
CompletableFuture<byte[]> future = invoices.getBytes(key).toFuture();
```

These are tested, with back-pressure through the JDK's `Flow`. Kotlin coroutines (`awaitSingle`, `asFlow` from
`kotlinx-coroutines-reactor`) work through the same publishers, but aren't tested yet.

## Testing your code

Use the in-memory provider and Reactor's `StepVerifier`:

```java
Halyx halyx = Halyx.builder().register("orders", ProviderConfig.of("memory")).build();
ReactiveHalyx rx = ReactiveHalyx.withVirtualThreads(halyx);

StepVerifier.create(rx.provider("orders").blobStore("b").head(BlobKey.of("missing")))
        .verifyComplete();
```

Calls run on real threads, so use `StepVerifier`'s waiting, not virtual time, for Halyx calls.

## Without the module

Applications that don't want `halyx-reactor` can wrap calls themselves, on a scheduler of their own:

```java
Mono<String> invoice = Mono.fromCallable(() ->
                new String(invoices.getBytes(BlobKey.of("2026/inv-1.txt")), StandardCharsets.UTF_8))
        .subscribeOn(scheduler);
```

Listings and subscriptions take more care: a listener that returns acks its message, so it must wait until the
pipeline has handled it; cancelling must nack what is unsettled; and the consumer must be closed off the
listener's thread, since Reactor can cancel from within it and closing waits for running listeners.
`ReactiveUsageTest` in `halyx-spring-boot-autoconfigure` shows each pattern by hand, and `halyx-reactor` packages them.
