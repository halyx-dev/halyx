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
package dev.halyx.keys;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.UnavailableException;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.memory.InMemoryKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Key service calls under concurrency: one per refresh or rotation however many callers, and wraps in parallel. */
class KeyConcurrencyTest {

    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);
    private static final int CALLERS = 50;

    /** Key service calls by operation, slowed so that callers overlap. */
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger mostInFlight = new AtomicInteger();
    private final AtomicBoolean failNextWrap = new AtomicBoolean();
    private volatile long delayMillis = 200;
    private Halyx halyx;

    @BeforeEach
    void start() {
        halyx = Halyx.builder()
                .register("a", ProviderConfig.of("memory"))
                .register("b", ProviderConfig.of("memory"))
                .register("c", ProviderConfig.of("memory"))
                .interceptor(new Interceptor() {
                    @Override
                    public <T> T intercept(OperationContext context, Invocation<T> next) {
                        if (!context.service().equals("keys")) {
                            return next.proceed();
                        }
                        calls.computeIfAbsent(context.operation(), op -> new AtomicInteger()).incrementAndGet();
                        mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        try {
                            Thread.sleep(delayMillis);
                            if (context.operation().equals("wrap") && failNextWrap.getAndSet(false)) {
                                throw new UnavailableException("the key service is down", null, "memory", null);
                            }
                            return next.proceed();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    }
                })
                .build();
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    private CryptoKey key(String provider, KeyStoreConfig config) {
        KeyStore store = halyx.provider(provider).keyStore(config);
        try {
            store.unwrap(InMemoryKeys.class).create("data", KeyPurpose.ENCRYPTION);
        } catch (IllegalArgumentException e) {
            // already created in this store
        }
        return store.key("data");
    }

    private int calls(String operation) {
        return calls.getOrDefault(operation, new AtomicInteger()).get();
    }

    @Test
    void manyCallersShareTheFirstMetadataRead() throws Exception {
        CryptoKey key = key("a", KeyStoreConfig.defaults());

        List<KeyMetadata> read = all(key::metadata);

        assertThat(read).hasSize(CALLERS).allSatisfy(m -> assertThat(m.name()).isEqualTo("data"));
        assertThat(calls("metadata")).isEqualTo(1);
    }

    @Test
    void aStaleCopyServesOtherCallersWhileOneRefreshes() throws Exception {
        CryptoKey key = key("a", KeyStoreConfig.defaults());
        KeyMetadata first = key.metadata();
        age(key);
        calls.clear();

        long started = System.nanoTime();
        List<KeyMetadata> read = all(key::metadata);

        assertThat(read).allSatisfy(m -> assertThat(m.keyId()).isEqualTo(first.keyId()));
        assertThat(calls("metadata")).as("one refresh, not one per caller").isEqualTo(1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(5 * delayMillis));
    }

    @Test
    void aReusedDataKeyIsWrappedOnceHoweverManyCallersArrive() throws Exception {
        CryptoKey key = key("a", KeyStoreConfig.builder().dataKeyReuse(Duration.ofMinutes(5), 1_000).build());
        key.metadata();
        calls.clear();

        List<SealedData> sealed = all(() -> key.encrypt(DATA, AAD));

        assertThat(calls("wrap")).isEqualTo(1);
        assertThat(sealed).extracting(SealedData::keys).containsOnly(sealed.get(0).keys());
        delayMillis = 0;
        sealed.forEach(s -> assertThat(key.decrypt(s, AAD)).isEqualTo(DATA));
    }

    @Test
    void callersWaitingOnAFailedRotationGetItsFailureAndTheNextOneTriesAgain() throws Exception {
        CryptoKey key = key("a", KeyStoreConfig.builder().dataKeyReuse(Duration.ofMinutes(5), 1_000).build());
        key.metadata();
        calls.clear();
        failNextWrap.set(true);

        List<Throwable> failures = new ArrayList<>();
        for (Future<SealedData> result : start(() -> key.encrypt(DATA, AAD))) {
            try {
                result.get();
            } catch (java.util.concurrent.ExecutionException e) {
                failures.add(e.getCause());
            }
        }

        assertThat(failures).isNotEmpty().allSatisfy(e -> assertThat(e).isInstanceOf(UnavailableException.class));
        assertThat(calls("wrap")).as("callers that waited on the failed wrap didn't each try their own")
                .isLessThanOrEqualTo(2);
        delayMillis = 0;
        assertThat(key.decrypt(key.encrypt(DATA, AAD), AAD)).isEqualTo(DATA);
    }

    @Test
    void sealingForSeveralKeysWrapsInParallel() {
        // Different locations, as keys on different clouds would be: one key id each.
        CryptoKey a = key("a", KeyStoreConfig.builder().location("ring-a").build());
        CryptoKey b = key("b", KeyStoreConfig.builder().location("ring-b").build());
        CryptoKey c = key("c", KeyStoreConfig.builder().location("ring-c").build());
        a.metadata();
        b.metadata();
        c.metadata();
        mostInFlight.set(0);

        long started = System.nanoTime();
        SealedData sealed = CryptoKeys.encrypt(DATA, AAD, a, b, c);

        assertThat(mostInFlight.get()).as("the three wraps overlapped").isEqualTo(3);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2 * delayMillis));
        assertThat(sealed.keys()).extracting(WrappedKey::keyId)
                .containsExactly(a.metadata().keyId(), b.metadata().keyId(), c.metadata().keyId());
        delayMillis = 0;
        assertThat(c.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThat(a.decrypt(sealed, AAD)).isEqualTo(DATA);
    }

    /**
     * The wraps of a several-key seal run in parallel, yet each starts on the caller's thread, where an asynchronous
     * interceptor such as the observation one picks up the caller's context: here a thread-local trace.
     */
    @Test
    void parallelWrapsKeepTheCallersContext() {
        ThreadLocal<String> trace = new ThreadLocal<>();
        List<String> traces = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (Halyx traced = Halyx.builder()
                .register("x", ProviderConfig.of("memory"))
                .register("y", ProviderConfig.of("memory"))
                .interceptor(new Interceptor() {
                    @Override
                    public <T> T intercept(OperationContext context, Invocation<T> next) {
                        return next.proceed();
                    }

                    @Override
                    public <T> java.util.concurrent.CompletableFuture<T> interceptAsync(OperationContext context,
                            dev.halyx.interceptor.AsyncInvocation<T> next, java.util.concurrent.Executor blocking) {
                        if (context.service().equals("keys") && context.operation().equals("wrap")) {
                            traces.add(String.valueOf(trace.get()));
                            threads.add(Thread.currentThread());
                        }
                        return next.proceed();
                    }
                })
                .build()) {
            CryptoKey x = memoryKey(traced, "x", "ring-x");
            CryptoKey y = memoryKey(traced, "y", "ring-y");
            x.metadata();
            y.metadata();

            trace.set("request-42");
            SealedData sealed;
            try {
                sealed = CryptoKeys.encrypt(DATA, AAD, x, y);
            } finally {
                trace.remove();
            }

            assertThat(traces).containsExactly("request-42", "request-42");
            assertThat(threads).containsOnly(Thread.currentThread());
            assertThat(y.decrypt(sealed, AAD)).isEqualTo(DATA);
        }
    }

    private static CryptoKey memoryKey(Halyx halyx, String provider, String location) {
        KeyStore store = halyx.provider(provider).keyStore(KeyStoreConfig.builder().location(location).build());
        store.unwrap(InMemoryKeys.class).create("data", KeyPurpose.ENCRYPTION);
        return store.key("data");
    }

    @Test
    void theDecryptCacheServesConcurrentHitsCorrectly() throws Exception {
        // Room for every data key, so nothing is evicted; misses aren't single-flight, so warm the cache first.
        CryptoKey key = key("a", KeyStoreConfig.builder().dataKeyCache(Duration.ofMinutes(5), 8).build());
        delayMillis = 0;
        List<SealedData> sealed = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            sealed.add(key.encrypt(("message " + i).getBytes(StandardCharsets.UTF_8), AAD));
        }
        for (SealedData data : sealed) {
            key.decrypt(data, AAD);
        }
        calls.clear();

        AtomicInteger next = new AtomicInteger();
        List<String> opened = all(() -> {
            int i = next.getAndIncrement() % sealed.size();
            return new String(key.decrypt(sealed.get(i), AAD), StandardCharsets.UTF_8).equals("message " + i)
                    ? "ok" : "wrong plaintext for " + i;
        });

        assertThat(opened).containsOnly("ok");
        assertThat(calls("unwrap")).as("every concurrent decrypt is a cache hit").isZero();
    }

    // Helpers

    /** Runs {@code action} on {@value #CALLERS} virtual threads at once and returns every result. */
    private <T> List<T> all(Supplier<T> action) throws Exception {
        List<T> results = new ArrayList<>();
        for (Future<T> result : start(action)) {
            results.add(result.get());
        }
        return results;
    }

    private <T> List<Future<T>> start(Supplier<T> action) throws InterruptedException {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> results = new ArrayList<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CALLERS; i++) {
                results.add(threads.submit(() -> {
                    go.await();
                    return action.get();
                }));
            }
            go.countDown();
        }
        return results;
    }

    /** Makes the key's cached metadata look older than its time to live. */
    private static void age(CryptoKey key) throws ReflectiveOperationException {
        Field readAt = DefaultCryptoKey.class.getDeclaredField("metadataReadAtNanos");
        readAt.setAccessible(true);
        readAt.setLong(key, System.nanoTime() - DefaultCryptoKey.METADATA_TTL.toNanos() - 1);
    }
}
