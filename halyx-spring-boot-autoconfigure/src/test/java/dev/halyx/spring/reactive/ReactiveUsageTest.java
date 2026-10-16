/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.halyx.spring.reactive;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.messaging.ConsumerOptions;
import dev.halyx.messaging.MessageConsumer;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.Subscription;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.QueryPage;
import dev.halyx.nosql.TableConfig;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The patterns in the reactive guide (docs/api/reactive.md), run against the in-memory provider: blocking calls
 * on a scheduler the application chooses, fan-out with a limit, paged queries as a Flux, and a subscription as
 * a Flux that acks each message only after the pipeline has handled it.
 */
class ReactiveUsageTest {

    private Halyx halyx;
    private Scheduler scheduler;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        scheduler = Schedulers.fromExecutorService(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), "halyx");
    }

    @AfterEach
    void stop() {
        scheduler.dispose();
        halyx.close();
    }

    @Test
    void singleCallsRunOnTheApplicationsScheduler() {
        BlobStore invoices = halyx.provider("mem").blobStore("invoices");
        invoices.put(BlobKey.of("2026/inv-1.txt"), BlobContent.ofString("paid"));
        Map<String, Boolean> virtual = new ConcurrentHashMap<>();

        String body = Mono.fromCallable(() -> {
                    virtual.put("get", Thread.currentThread().isVirtual());
                    return new String(invoices.getBytes(BlobKey.of("2026/inv-1.txt")), StandardCharsets.UTF_8);
                })
                .subscribeOn(scheduler)
                .block(Duration.ofSeconds(5));

        assertThat(body).isEqualTo("paid");
        assertThat(virtual).containsEntry("get", true);
    }

    @Test
    void fanOutWithAConcurrencyLimit() {
        DocumentStore orders = halyx.provider("mem").documentStore(TableConfig.builder("orders").sortKey("sk").build());
        IntStream.range(0, 50).forEach(i -> orders.put(Key.of("o-" + i, "v1"), Document.of(Map.of("n", i))));
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        List<Item> found = Flux.range(0, 50)
                .flatMap(i -> Mono.fromCallable(() -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(5);
                        return orders.get(Key.of("o-" + i, "v1")).orElseThrow();
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }).subscribeOn(scheduler), 8)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(found).hasSize(50);
        assertThat(peak.get()).isLessThanOrEqualTo(8);
    }

    @Test
    void pagedQueriesAsAFlux() {
        DocumentStore orders = halyx.provider("mem").documentStore(TableConfig.builder("orders").sortKey("sk").build());
        IntStream.range(0, 25).forEach(i -> orders.put(Key.of("c-1", String.format("%03d", i)), Document.of(Map.of("n", i))));

        Flux<Item> items = Mono.fromCallable(() -> orders.query(Query.builder("c-1").limit(10).build()))
                .expand(page -> page.nextPageToken()
                        .map(token -> Mono.fromCallable(() -> orders.query(Query.builder("c-1").limit(10).pageToken(token).build())))
                        .orElse(Mono.empty()))
                .subscribeOn(scheduler)
                .flatMapIterable(QueryPage::items);

        assertThat(items.map(i -> i.key().sort().orElseThrow()).collectList().block(Duration.ofSeconds(5)))
                .hasSize(25).isSorted();
    }

    /** One message on its way through the pipeline; completing {@code done} settles it. */
    record Delivery(ReceivedMessage message, CompletableFuture<Void> done) {
    }

    /**
     * A subscription as a Flux. The listener waits until the pipeline completes the delivery: returning acks the
     * message, throwing nacks it. Messages in flight are capped by the consumer's concurrency. Cancelling the
     * Flux fails the deliveries still in the pipeline, so they are nacked and redelivered, then stops the consumer.
     */
    static Flux<Delivery> deliveries(Subscription subscription, ConsumerOptions options) {
        return Flux.create(sink -> {
            Set<Delivery> pending = ConcurrentHashMap.newKeySet();
            MessageConsumer consumer = subscription.listen(message -> {
                Delivery delivery = new Delivery(message, new CompletableFuture<>());
                pending.add(delivery);
                try {
                    sink.next(delivery);
                    delivery.done().join(); // throws if the pipeline failed it, which nacks the message
                } finally {
                    pending.remove(delivery);
                }
            }, options);
            sink.onDispose(() -> {
                pending.forEach(d -> d.done().completeExceptionally(new CancellationException("pipeline cancelled")));
                // Cancelling can happen inside sink.next, on a listener's thread, and close() waits for running
                // listeners: close on a thread of its own.
                Thread.startVirtualThread(() -> {
                    try {
                        consumer.close();
                    } catch (Exception e) {
                        // already stopping; nothing left to tell the cancelled subscriber
                    }
                });
            });
        });
    }

    @Test
    void cancellingTheFluxNacksWhatIsStillInThePipeline() {
        Subscription billing = halyx.provider("mem").subscription(SubscriptionConfig.builder("billing").topic("orders").build());
        IntStream.range(0, 5).forEach(i -> halyx.provider("mem").publisher("orders").publish("order-" + i));
        ConsumerOptions options = ConsumerOptions.DEFAULTS.withConcurrency(4).withBackoff(Duration.ofMillis(10), Duration.ofMillis(10));
        long start = System.nanoTime();

        // Take the first delivery and never complete it: cancelling must release its listener, not leave
        // close() waiting for the shutdown timeout.
        Delivery first = deliveries(billing, options).next().block(Duration.ofSeconds(5));

        assertThat(first).isNotNull();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        List<ReceivedMessage> redelivered = new java.util.ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (redelivered.size() < 5 && System.nanoTime() < deadline) {
            billing.receive(10, Duration.ofMillis(200)).forEach(m -> {
                redelivered.add(m);
                m.ack();
            });
        }
        assertThat(redelivered).as("nothing acked by the cancelled pipeline").extracting(ReceivedMessage::bodyAsString)
                .contains(first.message().bodyAsString()).hasSize(5);
    }

    @Test
    void subscriptionAsAFluxAcksAfterProcessingAndRedeliversFailures() {
        Subscription billing = halyx.provider("mem").subscription(SubscriptionConfig.builder("billing").topic("orders").build());
        IntStream.range(0, 20).forEach(i -> halyx.provider("mem").publisher("orders").publish("order-" + i));
        Map<String, Integer> attempts = new ConcurrentHashMap<>();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        ConsumerOptions options = ConsumerOptions.DEFAULTS.withConcurrency(4).withBackoff(Duration.ofMillis(50), Duration.ofMillis(100));

        List<String> handled = deliveries(billing, options)
                .flatMap(d -> Mono.fromCallable(() -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        String body = d.message().bodyAsString();
                        if (attempts.merge(body, 1, Integer::sum) == 1 && body.equals("order-7")) {
                            throw new IllegalStateException("first attempt fails");
                        }
                        return body;
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }).subscribeOn(scheduler)
                        .doOnSuccess(body -> d.done().complete(null))
                        .onErrorResume(e -> {
                            d.done().completeExceptionally(e);
                            return Mono.empty();
                        }))
                .take(20)
                .collectList()
                .block(Duration.ofSeconds(10));

        assertThat(handled).hasSize(20).contains("order-7").doesNotHaveDuplicates();
        assertThat(attempts).containsEntry("order-7", 2);
        assertThat(peak.get()).isLessThanOrEqualTo(4);
        assertThat(billing.receive(10, Duration.ofMillis(200))).as("everything was acked").isEmpty();
    }
}
