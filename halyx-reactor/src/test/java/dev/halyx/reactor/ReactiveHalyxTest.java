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
package dev.halyx.reactor;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.NotFoundException;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.TableConfig;
import dev.halyx.storage.AsyncBlobStore;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.ListOptions;
import io.micrometer.context.ContextRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** What the adapters promise beyond the blocking API's behaviour, which the conformance kits cover. */
class ReactiveHalyxTest {

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    private Halyx halyx;
    private ReactiveHalyx rx;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    @BeforeAll
    static void registerTenant() {
        ContextRegistry.getInstance().registerThreadLocalAccessor("tenant", TENANT::get, TENANT::set, TENANT::remove);
    }

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        rx = ReactiveHalyx.withVirtualThreads(halyx);
    }

    @AfterEach
    void stop() {
        rx.close();
        halyx.close();
        TENANT.remove();
    }

    /** The memory store, counting calls by method name. */
    private BlobStore counted(BlobStore store) {
        return (BlobStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {BlobStore.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("async")) {
                        // Its asynchronous view calls back through this proxy, so every call is counted.
                        return AsyncBlobStore.onExecutor((BlobStore) proxy, (java.util.concurrent.Executor) args[0]);
                    }
                    calls.computeIfAbsent(method.getName(), n -> new AtomicInteger()).incrementAndGet();
                    try {
                        return method.invoke(store, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private int count(String method) {
        return calls.getOrDefault(method, new AtomicInteger()).get();
    }

    @Test
    void callsRunOnlyWhenSubscribedAndAgainOnEachSubscription() {
        ReactiveBlobStore store = rx.wrap(counted(halyx.provider("mem").blobStore("b")));
        Mono<Boolean> exists = store.exists(BlobKey.of("k"));

        assertThat(count("exists")).isZero();
        StepVerifier.create(exists).expectNext(false).verifyComplete();
        StepVerifier.create(exists.repeat(2)).expectNext(false, false, false).verifyComplete();

        assertThat(count("exists")).isEqualTo(4);
    }

    @Test
    void callsRunOnTheGivenSchedulerAndErrorsAreTheBlockingOnes() {
        Scheduler scheduler = ReactiveHalyx.virtualThreadScheduler("app-calls");
        try (ReactiveHalyx own = ReactiveHalyx.of(halyx, scheduler)) {
            ReactiveBlobStore store = own.provider("mem").blobStore("b");

            String thread = store.put(BlobKey.of("k"), BlobContent.ofString("v"))
                    .map(r -> Thread.currentThread().getName()).block();
            assertThat(thread).startsWith("app-calls-");

            StepVerifier.create(store.getBytes(BlobKey.of("missing"))).verifyError(NotFoundException.class);
            StepVerifier.create(store.head(BlobKey.of("missing"))).verifyComplete();
        }
        assertThat(scheduler.isDisposed()).as("the application's scheduler stays the application's").isFalse();
        scheduler.dispose();
    }

    @Test
    void closeDisposesOnlyASchedulerItCreated() {
        Scheduler mine = rx.scheduler();

        rx.close();

        assertThat(mine.isDisposed()).isTrue();
    }

    @Test
    void maxConcurrencyBoundsCallsInFlight() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        BlobStore slow = (BlobStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {BlobStore.class},
                (proxy, method, args) -> {
                    peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    try {
                        Thread.sleep(20);
                        return method.getName().equals("exists") ? Boolean.TRUE : null;
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
        try (ReactiveHalyx limited = ReactiveHalyx.builder(halyx).maxConcurrency(3).build()) {
            ReactiveBlobStore store = limited.wrap(slow);

            List<Boolean> all = Flux.range(0, 30).flatMap(i -> store.exists(BlobKey.of("k" + i)), 30)
                    .collectList().block(Duration.ofSeconds(10));

            assertThat(all).hasSize(30);
            assertThat(peak.get()).isLessThanOrEqualTo(3).isGreaterThan(1);
        }
    }

    @Test
    void callsSeeTheCallersThreadLocalsAndTheSubscribersContext() {
        BlobStore recording = (BlobStore) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {BlobStore.class},
                (proxy, method, args) ->
                    method.getName().equals("exists") ? Boolean.valueOf("acme".equals(TENANT.get())) : null);
        ReactiveBlobStore recorded = rx.wrap(recording);

        StepVerifier.create(recorded.exists(BlobKey.of("k")).contextWrite(Context.of("tenant", "acme")))
                .expectNext(true).verifyComplete();

        TENANT.set("acme");
        StepVerifier.create(recorded.exists(BlobKey.of("k"))).expectNext(true).verifyComplete();
        TENANT.remove();
        StepVerifier.create(recorded.exists(BlobKey.of("k"))).expectNext(false).verifyComplete();
    }

    @Test
    void pagesAreReadAsTheSubscriberAsksAtMostOneAheadAndNotAfterCancel() throws InterruptedException {
        BlobStore memory = halyx.provider("mem").blobStore("b");
        IntStream.range(0, 50).forEach(i -> memory.put(BlobKey.of(String.format("p/%03d", i)), BlobContent.ofString("x")));
        ReactiveBlobStore store = rx.wrap(counted(memory));
        ListOptions pagesOfTen = ListOptions.builder().prefix("p/").pageSize(10).build();

        StepVerifier.create(store.listAll(pagesOfTen), 5)
                .expectNextCount(5)
                .then(() -> assertThat(count("list")).as("five items: their page, and one ahead").isBetween(1, 2))
                .thenRequest(10)
                .expectNextCount(10)
                .then(() -> assertThat(count("list")).as("fifteen items: two pages, and one ahead").isBetween(2, 3))
                .thenCancel()
                .verify(Duration.ofSeconds(5));
        // A page read already started on the scheduler when the subscriber cancelled still finishes
        Thread.sleep(200);
        int afterCancel = count("list");
        Thread.sleep(200);
        assertThat(count("list")).as("nothing after cancel").isEqualTo(afterCancel);
        assertThat(afterCancel).as("never the whole listing").isLessThan(5);

        assertThat(store.listAll(pagesOfTen).count().block()).isEqualTo(50);
    }

    @Test
    void queriesFollowPageTokens() {
        DocumentStore docs = halyx.provider("mem").documentStore(TableConfig.builder("t").sortKey("sk").build());
        IntStream.range(0, 25).forEach(i -> docs.put(Key.of("p", String.format("%02d", i)), Document.of(Map.of("n", i))));
        ReactiveDocumentStore store = rx.wrap(docs);

        StepVerifier.create(store.queryAll(Query.builder("p").limit(10).build()).map(item -> item.key().sort().orElseThrow()))
                .expectNextSequence(IntStream.range(0, 25).mapToObj(i -> String.format("%02d", i)).toList())
                .verifyComplete();
        StepVerifier.create(store.get(Key.of("p", "99"))).verifyComplete();
    }

    @Test
    void aWithLockCancelledAsItAcquiresTheLockGivesItBack() {
        ReactiveCache<String> cache = rx.provider("mem").cache(dev.halyx.cache.CacheConfig.builder(
                java.net.URI.create("redis://memory:6379")).namespace("cancelled-locks").build());

        // Cancelled at once, many times: some cancellations land after the lock is taken but before the work has it
        for (int i = 0; i < 300; i++) {
            String name = "job-" + i;
            cache.withLock(name, Duration.ofMinutes(1), Duration.ZERO, lock -> Mono.never()).subscribe().dispose();
            StepVerifier.create(Mono.defer(() -> cache.tryLock(name, Duration.ofMinutes(1)))
                            .repeatWhenEmpty(100, attempts -> attempts.delayElements(Duration.ofMillis(20)))
                            .flatMap(cache::release))
                    .as("lock " + name).expectNext(true).verifyComplete();
        }
    }

    @Test
    void cacheLocksAreReleasedHoweverTheWorkEnds() {
        ReactiveCache<String> cache = rx.provider("mem").cache(dev.halyx.cache.CacheConfig.builder(
                java.net.URI.create("redis://memory:6379")).namespace("locks").build());

        StepVerifier.create(cache.withLock("job", Duration.ofMinutes(1), Duration.ZERO, lock -> Mono.just("done")))
                .expectNext("done").verifyComplete();
        StepVerifier.create(cache.withLock("job", Duration.ofMinutes(1), Duration.ZERO,
                lock -> Mono.<String>error(new IllegalStateException("failed")))).verifyError(IllegalStateException.class);
        StepVerifier.create(cache.withLock("job", Duration.ofMinutes(1), Duration.ZERO, lock -> Mono.never()))
                .expectSubscription().thenCancel().verify(Duration.ofSeconds(5));

        // released every time, so it is free again. A cancellation releases asynchronously: cancelling can't wait for
        // the release, so retry the lock briefly rather than expecting it free the instant thenCancel() returns.
        StepVerifier.create(Mono.defer(() -> cache.tryLock("job", Duration.ofMinutes(1)))
                        .repeatWhenEmpty(50, attempts -> attempts.delayElements(Duration.ofMillis(20)))
                        .flatMap(cache::release))
                .expectNext(true).verifyComplete();
    }
}
