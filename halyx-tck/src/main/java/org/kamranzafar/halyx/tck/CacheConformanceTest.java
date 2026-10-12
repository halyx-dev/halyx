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
package org.kamranzafar.halyx.tck;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.kamranzafar.halyx.CloudContext;
import org.kamranzafar.halyx.cache.Cache;
import org.kamranzafar.halyx.cache.CacheConfig;
import org.kamranzafar.halyx.cache.CacheLock;
import org.kamranzafar.halyx.cache.CacheStatistics;
import org.kamranzafar.halyx.cache.Codec;
import org.kamranzafar.halyx.error.InvalidArgumentException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for {@link Cache}. Every provider extends it and supplies a context plus the
 * endpoint and auth of a running Redis-compatible server. Each test uses a fresh random namespace,
 * so tests never interfere and the server can be shared.
 */
public abstract class CacheConformanceTest {

    private String namespace;

    /** The provider context under test. */
    protected abstract CloudContext context();

    /** Endpoint, auth and provider properties of the server under test; the TCK sets the namespace. */
    protected abstract CacheConfig.Builder config();

    /** Test names to skip in this environment, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newNamespace(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        namespace = "tck-" + UUID.randomUUID();
    }

    protected Cache<String> cache() {
        return context().cache(config().namespace(namespace).build());
    }

    private Cache<String> cacheWithDefaultTtl(Duration ttl) {
        return context().cache(config().namespace(namespace).defaultTtl(ttl).build());
    }

    private static void awaitAbsent(Cache<?> cache, String key) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(5);
        while (cache.exists(key)) {
            assertThat(Instant.now()).as("%s should have expired", key).isBefore(deadline);
            Thread.sleep(50);
        }
    }

    // --- basics --------------------------------------------------------------------------------

    @Test
    void putThenGet() {
        cache().put("greeting", "hello");

        assertThat(cache().get("greeting")).hasValue("hello");
    }

    @Test
    void missingKeyIsEmpty() {
        assertThat(cache().get("missing")).isEmpty();
        assertThat(cache().exists("missing")).isFalse();
    }

    @Test
    void putOverwrites() {
        cache().put("k", "v1");
        cache().put("k", "v2");

        assertThat(cache().get("k")).hasValue("v2");
    }

    @Test
    void deleteReportsWhetherKeyExisted() {
        cache().put("k", "v");

        assertThat(cache().delete("k")).isTrue();
        assertThat(cache().delete("k")).isFalse();
        assertThat(cache().get("k")).isEmpty();
    }

    @Test
    void unicodeKeysAndValues() {
        cache().put("ключ:キー", "värde ✓");

        assertThat(cache().get("ключ:キー")).hasValue("värde ✓");
    }

    @Test
    void binaryValuesWithBytesCodec() {
        byte[] data = new byte[256];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        Cache<byte[]> bytes = context().cache(config().namespace(namespace).build(), Codec.bytes());
        bytes.put("binary", data);

        assertThat(bytes.get("binary")).hasValueSatisfying(v -> assertThat(v).isEqualTo(data));
    }

    @Test
    void largeValue() {
        byte[] data = new byte[1024 * 1024];
        new Random(1).nextBytes(data);
        Cache<byte[]> bytes = context().cache(config().namespace(namespace).build(), Codec.bytes());
        bytes.put("large", data);

        assertThat(bytes.get("large")).hasValueSatisfying(v -> assertThat(v).isEqualTo(data));
    }

    @Test
    void customCodec() {
        Codec<Integer> ints = Codec.of(i -> i.toString().getBytes(StandardCharsets.UTF_8),
                b -> Integer.parseInt(new String(b, StandardCharsets.UTF_8)));
        Cache<Integer> numbers = context().cache(config().namespace(namespace).build(), ints);
        numbers.put("answer", 42);

        assertThat(numbers.get("answer")).hasValue(42);
    }

    @Test
    void namespacesAreIsolated() {
        Cache<String> a = context().cache(config().namespace(namespace + "-a").build());
        Cache<String> b = context().cache(config().namespace(namespace + "-b").build());
        a.put("shared-key", "from a");

        assertThat(b.get("shared-key")).isEmpty();
        assertThat(a.get("shared-key")).hasValue("from a");
    }

    // --- expiry --------------------------------------------------------------------------------

    @Test
    void entriesExpire() throws Exception {
        cache().put("short-lived", "v", Duration.ofMillis(300));

        assertThat(cache().exists("short-lived")).isTrue();
        awaitAbsent(cache(), "short-lived");
        assertThat(cache().get("short-lived")).isEmpty();
    }

    @Test
    void ttlReportsRemainingTime() {
        cache().put("k", "v", Duration.ofSeconds(60));

        assertThat(cache().ttl("k")).hasValueSatisfying(ttl ->
                assertThat(ttl).isBetween(Duration.ofSeconds(50), Duration.ofSeconds(60)));
    }

    @Test
    void withoutTtlEntriesDoNotExpire() {
        cache().put("forever", "v");

        assertThat(cache().ttl("forever")).isEmpty();
        assertThat(cache().exists("forever")).isTrue();
    }

    @Test
    void defaultTtlApplies() {
        Cache<String> withDefault = cacheWithDefaultTtl(Duration.ofSeconds(60));
        withDefault.put("k", "v");

        assertThat(withDefault.ttl("k")).hasValueSatisfying(ttl -> assertThat(ttl).isPositive());
    }

    @Test
    void expireSetsNewTtl() throws Exception {
        cache().put("k", "v");

        assertThat(cache().expire("k", Duration.ofMillis(300))).isTrue();
        awaitAbsent(cache(), "k");
    }

    @Test
    void expireOnMissingKeyReturnsFalse() {
        assertThat(cache().expire("missing", Duration.ofSeconds(10))).isFalse();
    }

    @Test
    void ttlOfMissingKeyIsEmpty() {
        assertThat(cache().ttl("missing")).isEmpty();
    }

    @Test
    void nonPositiveTtlIsRejected() {
        assertThatThrownBy(() -> cache().put("k", "v", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache().put("k", "v", Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }

    // --- conditional and bulk writes -----------------------------------------------------------

    @Test
    void putIfAbsentOnlyStoresOnce() {
        assertThat(cache().putIfAbsent("lock", "owner-1")).isTrue();
        assertThat(cache().putIfAbsent("lock", "owner-2")).isFalse();

        assertThat(cache().get("lock")).hasValue("owner-1");
    }

    @Test
    void putIfAbsentWithTtlExpires() throws Exception {
        assertThat(cache().putIfAbsent("lease", "a", Duration.ofMillis(300))).isTrue();

        awaitAbsent(cache(), "lease");
        assertThat(cache().putIfAbsent("lease", "b", Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void putAllAndGetAll() {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            entries.put("k" + i, "v" + i);
        }
        cache().putAll(entries);

        List<String> keys = new ArrayList<>(entries.keySet());
        keys.add("missing");
        Map<String, String> found = cache().getAll(keys);

        assertThat(found).containsExactlyInAnyOrderEntriesOf(entries);
    }

    @Test
    void putAllWithTtl() {
        cache().putAll(Map.of("a", "1", "b", "2"), Duration.ofSeconds(60));

        assertThat(cache().ttl("a")).isPresent();
        assertThat(cache().ttl("b")).isPresent();
    }

    @Test
    void deleteAllCountsExistingKeys() {
        cache().putAll(Map.of("a", "1", "b", "2", "c", "3"));

        assertThat(cache().deleteAll(List.of("a", "b", "missing"))).isEqualTo(2);
        assertThat(cache().getAll(List.of("a", "b", "c"))).containsOnlyKeys("c");
    }

    @Test
    void emptyBulkOperations() {
        assertThat(cache().getAll(List.of())).isEmpty();
        assertThat(cache().deleteAll(List.of())).isZero();
        cache().putAll(Map.of());
    }

    // --- counters ------------------------------------------------------------------------------

    @Test
    void incrementCreatesAndAdds() {
        assertThat(cache().increment("hits", 1)).isEqualTo(1);
        assertThat(cache().increment("hits", 5)).isEqualTo(6);
        assertThat(cache().increment("hits", -2)).isEqualTo(4);
        assertThat(cache().increment("hits", 0)).isEqualTo(4);
    }

    @Test
    void incrementKeepsTtl() {
        cache().increment("rate", 1);
        cache().expire("rate", Duration.ofSeconds(60));
        cache().increment("rate", 1);

        assertThat(cache().ttl("rate")).isPresent();
    }

    @Test
    void incrementOnNonNumberFails() {
        cache().put("text", "not a number");

        assertThatThrownBy(() -> cache().increment("text", 1)).isInstanceOf(InvalidArgumentException.class);
    }

    // --- cache-aside ---------------------------------------------------------------------------

    @Test
    void getOrLoadLoadsOnceThenServesFromCache() {
        AtomicInteger loads = new AtomicInteger();

        assertThat(cache().getOrLoad("user:1", () -> "alice-" + loads.incrementAndGet())).isEqualTo("alice-1");
        assertThat(cache().getOrLoad("user:1", () -> "alice-" + loads.incrementAndGet())).isEqualTo("alice-1");
        assertThat(loads).hasValue(1);
    }

    @Test
    void getOrLoadWithTtl() {
        cache().getOrLoad("k", Duration.ofSeconds(60), () -> "v");

        assertThat(cache().ttl("k")).isPresent();
    }

    @Test
    void concurrentGetOrLoadSharesOneLoad() throws Exception {
        Cache<String> shared = cache();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return shared.getOrLoad("expensive", () -> {
                        loads.incrementAndGet();
                        sleep(300);
                        return "computed";
                    });
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo("computed");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    void nullLoadResultIsNotCached() {
        assertThat(cache().getOrLoad("nothing", () -> null)).isNull();
        assertThat(cache().exists("nothing")).isFalse();
    }

    @Test
    void failingLoaderCachesNothing() {
        assertThatThrownBy(() -> cache().getOrLoad("broken", () -> {
            throw new IllegalStateException("database down");
        })).hasMessage("database down");

        assertThat(cache().exists("broken")).isFalse();
        assertThat(cache().getOrLoad("broken", () -> "recovered")).isEqualTo("recovered");
    }

    // --- atomic operations ---------------------------------------------------------------------

    @Test
    void updateReplacesExistingValueAndKeepsTtl() {
        cache().put("session", "v1", Duration.ofSeconds(60));

        assertThat(cache().update("session", "v2")).isTrue();
        assertThat(cache().get("session")).hasValue("v2");
        assertThat(cache().ttl("session")).hasValueSatisfying(ttl -> assertThat(ttl).isGreaterThan(Duration.ofSeconds(50)));
    }

    @Test
    void updateOfMissingKeyStoresNothing() {
        assertThat(cache().update("missing", "v")).isFalse();
        assertThat(cache().exists("missing")).isFalse();
    }

    @Test
    void replaceIsCompareAndSet() {
        cache().put("state", "draft", Duration.ofSeconds(60));

        assertThat(cache().replace("state", "published", "archived")).isFalse();
        assertThat(cache().replace("state", "draft", "published")).isTrue();
        assertThat(cache().get("state")).hasValue("published");
        assertThat(cache().ttl("state")).as("TTL kept").isPresent();
        assertThat(cache().replace("missing", "a", "b")).isFalse();
    }

    @Test
    void getAndDeleteConsumesOnce() {
        cache().put("otp", "123456");

        assertThat(cache().getAndDelete("otp")).hasValue("123456");
        assertThat(cache().getAndDelete("otp")).isEmpty();
        assertThat(cache().exists("otp")).isFalse();
    }

    @Test
    void deleteIfEqualsOnlyDeletesMatchingValue() {
        cache().put("owner", "a");

        assertThat(cache().deleteIfEquals("owner", "b")).isFalse();
        assertThat(cache().exists("owner")).isTrue();
        assertThat(cache().deleteIfEquals("owner", "a")).isTrue();
        assertThat(cache().exists("owner")).isFalse();
    }

    @Test
    void incrementWithTtlStartsAWindowOnce() throws Exception {
        assertThat(cache().increment("window", 1, Duration.ofMillis(800))).isEqualTo(1);
        Duration first = cache().ttl("window").orElseThrow();
        Thread.sleep(200);
        assertThat(cache().increment("window", 1, Duration.ofMillis(800))).isEqualTo(2);

        assertThat(cache().ttl("window").orElseThrow()).as("window not extended").isLessThan(first);
        awaitAbsent(cache(), "window");
        assertThat(cache().increment("window", 1, Duration.ofSeconds(60))).as("new window").isEqualTo(1);
    }

    // --- clear ---------------------------------------------------------------------------------

    @Test
    void clearRemovesOnlyThisNamespace() {
        Cache<String> mine = cache();
        Cache<String> other = context().cache(config().namespace(namespace + "-other").build());
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < 1200; i++) {
            entries.put("k" + i, "v");
        }
        mine.putAll(entries);
        other.put("k0", "kept");

        assertThat(mine.clear()).isEqualTo(1200);
        assertThat(mine.exists("k0")).isFalse();
        assertThat(mine.exists("k1199")).isFalse();
        assertThat(other.get("k0")).hasValue("kept");
    }

    @Test
    void clearMatchesNamespaceLiterally() {
        Cache<String> globby = context().cache(config().namespace(namespace + "-a*").build());
        Cache<String> lookalike = context().cache(config().namespace(namespace + "-ab").build());
        globby.put("k", "v");
        lookalike.put("k", "v");

        assertThat(globby.clear()).isEqualTo(1);
        assertThat(lookalike.exists("k")).isTrue();
    }

    @Test
    void clearWithoutNamespaceIsRefused() {
        Cache<String> global = context().cache(config().build());

        assertThatThrownBy(global::clear).isInstanceOf(IllegalStateException.class);
    }

    // --- locks ---------------------------------------------------------------------------------

    @Test
    void lockIsExclusiveUntilReleased() {
        Optional<CacheLock> first = cache().tryLock("job", Duration.ofSeconds(30));

        assertThat(first).isPresent();
        assertThat(cache().tryLock("job", Duration.ofSeconds(30))).isEmpty();
        assertThat(first.get().release()).isTrue();
        assertThat(cache().tryLock("job", Duration.ofSeconds(30))).isPresent();
    }

    @Test
    void expiredLeaseCannotReleaseTheNextHoldersLock() throws Exception {
        CacheLock stale = cache().tryLock("job", Duration.ofMillis(300)).orElseThrow();
        Instant deadline = Instant.now().plusSeconds(5);
        Optional<CacheLock> next = Optional.empty();
        while (next.isEmpty()) {
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(50);
            next = cache().tryLock("job", Duration.ofSeconds(30));
        }

        assertThat(stale.release()).isFalse();
        assertThat(stale.extend(Duration.ofSeconds(30))).isFalse();
        assertThat(cache().tryLock("job", Duration.ofSeconds(30))).as("still held by next").isEmpty();
        assertThat(next.get().release()).isTrue();
    }

    @Test
    void extendKeepsTheLockAlive() throws Exception {
        CacheLock lock = cache().tryLock("job", Duration.ofMillis(400)).orElseThrow();

        assertThat(lock.extend(Duration.ofSeconds(30))).isTrue();
        Thread.sleep(600);
        assertThat(cache().tryLock("job", Duration.ofSeconds(1))).isEmpty();
        lock.release();
    }

    @Test
    void lockWaitsForTheHolder() throws Exception {
        CacheLock holder = cache().tryLock("job", Duration.ofSeconds(30)).orElseThrow();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<CacheLock>> waiter = pool.submit(() -> cache().lock("job", Duration.ofSeconds(30), Duration.ofSeconds(5)));
            Thread.sleep(300);
            holder.release();

            Optional<CacheLock> acquired = waiter.get();
            assertThat(acquired).isPresent();
            acquired.get().release();
        } finally {
            pool.shutdownNow();
        }
        assertThat(cache().lock("job2", Duration.ofSeconds(30), Duration.ofMillis(10))).isPresent();
    }

    @Test
    void lockGivesUpAfterWaiting() {
        CacheLock holder = cache().tryLock("busy", Duration.ofSeconds(30)).orElseThrow();
        try {
            assertThat(cache().lock("busy", Duration.ofSeconds(30), Duration.ofMillis(200))).isEmpty();
        } finally {
            holder.release();
        }
    }

    // --- statistics ----------------------------------------------------------------------------

    @Test
    void statisticsCountHitsMissesAndLoads() {
        Cache<String> c = cache();
        c.put("a", "1");
        c.get("a");
        c.get("missing");
        c.getAll(List.of("a", "b"));
        c.getOrLoad("loaded", () -> "x");
        c.delete("a");

        CacheStatistics stats = c.statistics();
        assertThat(stats.hits()).isEqualTo(2);
        assertThat(stats.misses()).isEqualTo(3);
        assertThat(stats.loads()).isEqualTo(1);
        assertThat(stats.puts()).isEqualTo(2);
        assertThat(stats.evictions()).isEqualTo(1);
        assertThat(stats.hitRatio()).isEqualTo(0.4);
    }

    // --- misc ----------------------------------------------------------------------------------

    @Test
    void invalidKeysAreRejected() {
        assertThatThrownBy(() -> cache().put("", "v")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache().put(null, "v")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> cache().put("k", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void unwrapUnknownTypeThrows() {
        assertThatThrownBy(() -> cache().unwrap(StringBuilder.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsProviderAndNamespace() {
        assertThat(cache().provider()).isEqualTo(context().providerId());
        assertThat(cache().namespace()).isEqualTo(namespace);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
