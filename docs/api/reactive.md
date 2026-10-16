---
title: Reactive and async code
parent: API guide
nav_order: 11
---

# Using Halyx from reactive and async code
{: .no_toc }

Halyx's calls block, and every service object is thread-safe, as with JDBC. Reactive and async applications
wrap the calls themselves, so the application decides which threads run them, how many run at once, and how
context such as tracing travels with them. Halyx doesn't hide an executor of its own.
[R2DBC](sql.md#reactive-r2dbc) is the exception: `halyx-r2dbc` is non-blocking already.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

The examples use [Project Reactor](https://projectreactor.io); the same ideas apply to `CompletableFuture`,
Kotlin coroutines or Vert.x. Every example is run by `ReactiveUsageTest`, on Spring Boot 3.5 and 4.

## Choose the threads

Never call Halyx on an event loop: a blocking call there stalls every request sharing the thread. Run calls on
a scheduler of your own. Virtual threads suit Halyx's blocking calls well:

```java
Scheduler halyx = Schedulers.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor(), "halyx");
```

Or, on Reactor 3.6 or later and Java 21 or later, put Reactor's `boundedElastic` scheduler on virtual threads:

```
-Dreactor.schedulers.defaultBoundedElasticOnVirtualThreads=true
```

A scheduler of your own also isolates services from each other: give a slow one its own scheduler, or a
concurrency limit, so it can't hold up the rest.

## Single calls

```java
Mono<String> invoice = Mono.fromCallable(() ->
                new String(invoices.getBytes(BlobKey.of("2026/inv-1.txt")), StandardCharsets.UTF_8))
        .subscribeOn(halyx);
```

`fromCallable` runs the call only when subscribed, so `retry()` calls Halyx again. Halyx's own
[retries and interceptors](errors.md) still apply inside the call; avoid retrying the same failures twice.

## Fan-out with a limit

```java
Flux.range(0, 50)
    .flatMap(i -> Mono.fromCallable(() -> orders.get(Key.of("o-" + i, "v1")).orElseThrow())
            .subscribeOn(halyx), 8)          // at most 8 calls at once
    .collectList();
```

The `flatMap` concurrency argument bounds calls in flight, which also bounds the load on the cloud service and
its throttling.

## Paged queries as a `Flux`

```java
Flux<Item> items = Mono.fromCallable(() -> orders.query(Query.builder("c-1").limit(100).build()))
        .expand(page -> page.nextPageToken()
                .map(token -> Mono.fromCallable(() ->
                        orders.query(Query.builder("c-1").limit(100).pageToken(token).build())))
                .orElse(Mono.empty()))
        .subscribeOn(halyx)
        .flatMapIterable(QueryPage::items);
```

Each page is fetched only when the previous one has been consumed, so a slow subscriber holds back the reads.

## Subscriptions as a `Flux`

A [listener](messaging.md) that returns normally **acks** its message, and one that throws nacks it for
redelivery. So a listener that hands its message to a `Flux` must wait until the pipeline has handled it,
otherwise the message is acked before it has been processed:

```java
record Delivery(ReceivedMessage message, CompletableFuture<Void> done) {}

static Flux<Delivery> deliveries(Subscription subscription, ConsumerOptions options) {
    return Flux.create(sink -> {
        Set<Delivery> pending = ConcurrentHashMap.newKeySet();
        MessageConsumer consumer = subscription.listen(message -> {
            Delivery delivery = new Delivery(message, new CompletableFuture<>());
            pending.add(delivery);
            try {
                sink.next(delivery);
                delivery.done().join();    // returns: acked; throws: nacked and redelivered
            } finally {
                pending.remove(delivery);
            }
        }, options);
        sink.onDispose(() -> {
            // fail what the cancelled pipeline still holds, so it is nacked and redelivered
            pending.forEach(d -> d.done().completeExceptionally(new CancellationException("pipeline cancelled")));
            // cancelling can happen inside sink.next, on a listener's thread, and close() waits for
            // running listeners: close on a thread of its own
            Thread.startVirtualThread(() -> {
                try {
                    consumer.close();
                } catch (Exception e) {
                    // already stopping
                }
            });
        });
    });
}
```

The pipeline completes each delivery when it is done with it:

```java
deliveries(billing, ConsumerOptions.DEFAULTS.withConcurrency(16))
    .flatMap(d -> Mono.fromCallable(() -> bill(d.message()))
            .subscribeOn(halyx)
            .doOnSuccess(result -> d.done().complete(null))
            .onErrorResume(e -> {
                d.done().completeExceptionally(e);
                return Mono.empty();
            }))
    .subscribe();
```

This gives back-pressure for free: each waiting listener is a parked virtual thread, and the consumer stops
pulling while all `concurrency` listeners are busy, so a slow pipeline slows delivery rather than buffering
messages. Leases are extended while listeners wait, as for any listener (`extendLease`, on by default).

Two details matter, and both are tested:

- **Cancelling.** When the `Flux` is cancelled, deliveries still in the pipeline are failed, so they are nacked
  and redelivered rather than acked unprocessed.
- **Closing.** Reactor can cancel from inside `sink.next`, which runs on a listener's thread, and
  `MessageConsumer.close()` waits for running listeners. Closing on that thread would wait for itself until
  the shutdown timeout, so the consumer is closed on a thread of its own.

For [event buses](events.md), decode each delivery with `CloudEvents.decode(d.message())`.

## Context: logging and tracing

Reactor carries context in its `Context`, not in thread-locals, while Halyx's interceptors and observations
run on the thread that makes the call. With Micrometer's context propagation on the classpath, turn on
Reactor's automatic propagation so MDC and the current observation reach the Halyx call:

```java
Hooks.enableAutomaticContextPropagation();
```

Spring Boot does this when `spring.reactor.context-propagation=auto`.

## In Spring WebFlux

The Halyx beans inject as usual; wrap their calls as above in controllers and services. A controller returns
the `Mono` (WebFlux accepts a `CompletableFuture` too, for code written that way):

```java
@GetMapping("/orders/{id}")
Mono<Order> order(@PathVariable String id) {
    return Mono.fromCallable(() -> orders.get(Key.of(id)).map(Order::from).orElseThrow())
            .subscribeOn(halyx);
}
```

`@HalyxListener` methods already run on Halyx's virtual threads, off the event loop, so they work unchanged
in a WebFlux application. For data, Spring Data R2DBC over `halyx.r2dbc-databases` is fully reactive.

## What wrapping doesn't give

Each call still occupies a thread while it waits on the network; on virtual threads that is cheap, but it
isn't non-blocking I/O. Reactive repositories over document stores, and ready-made adapters taking your
scheduler, are on the [roadmap](../roadmap.md).
