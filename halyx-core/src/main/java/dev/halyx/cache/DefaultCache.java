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
package dev.halyx.cache;

import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.error.CloudException;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * The portable {@link Cache}: applies the codec, namespace, default TTL, validation, statistics,
 * fail-open policy, interceptors and exception translation around a provider {@link CacheDriver}.
 */
public final class DefaultCache<V> implements Cache<V> {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultCache.class);
    private static final String SERVICE = "cache";
    private static final int BREAKER_THRESHOLD = 5;
    private static final Duration BREAKER_OPEN_FOR = Duration.ofSeconds(10);

    /** How an operation behaves when the server is unavailable and fail-open is on. */
    private enum Mode {
        /** Return the fallback (a miss, false, zero). */
        BEST_EFFORT,
        /** Always fail: the caller relies on the outcome. */
        STRICT
    }

    private final String provider;
    private final CacheConfig config;
    private final Codec<V> codec;
    private final CacheDriver driver;
    private final InterceptorChain interceptors;
    private final String prefix;
    private final CacheStatistics statistics = new CacheStatistics();
    private final CircuitBreaker breaker = new CircuitBreaker(BREAKER_THRESHOLD, BREAKER_OPEN_FOR);
    private final ConcurrentMap<String, CompletableFuture<V>> loading = new ConcurrentHashMap<>();

    public DefaultCache(String provider, CacheConfig config, Codec<V> codec, CacheDriver driver,
                        InterceptorChain interceptors) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.config = Objects.requireNonNull(config, "config");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.driver = Objects.requireNonNull(driver, "driver");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
        this.prefix = config.namespace().isEmpty() ? "" : config.namespace() + ":";
    }

    @Override
    public String provider() {
        return provider;
    }

    @Override
    public String namespace() {
        return config.namespace();
    }

    @Override
    public CacheStatistics statistics() {
        return statistics;
    }

    // --- reads ---------------------------------------------------------------------------------

    @Override
    public Optional<V> get(String key) {
        String full = fullKey(key);
        Optional<byte[]> value = execute("get", key, true, Mode.BEST_EFFORT, Optional::empty, () -> driver.get(full));
        countLookup(value.isPresent());
        return value.map(codec::decode);
    }

    @Override
    public Map<String, V> getAll(Collection<String> keys) {
        List<String> unique = keys.stream().distinct().toList();
        if (unique.isEmpty()) {
            return Map.of();
        }
        List<String> full = unique.stream().map(this::fullKey).toList();
        Map<String, byte[]> found = execute("getAll", "[" + unique.size() + " keys]", true, Mode.BEST_EFFORT,
                Map::of, () -> driver.getAll(full));
        Map<String, V> result = new LinkedHashMap<>();
        for (String key : unique) {
            byte[] value = found.get(prefix + key);
            if (value != null) {
                result.put(key, codec.decode(value));
            }
        }
        statistics.hit(result.size());
        statistics.miss(unique.size() - result.size());
        return result;
    }

    @Override
    public boolean exists(String key) {
        String full = fullKey(key);
        return execute("exists", key, true, Mode.BEST_EFFORT, () -> false, () -> driver.exists(full));
    }

    @Override
    public Optional<Duration> ttl(String key) {
        String full = fullKey(key);
        return execute("ttl", key, true, Mode.BEST_EFFORT, Optional::empty, () -> driver.ttl(full));
    }

    // --- writes --------------------------------------------------------------------------------

    @Override
    public void put(String key, V value) {
        write(key, value, config.defaultTtl());
    }

    @Override
    public void put(String key, V value, Duration ttl) {
        write(key, value, Optional.of(requirePositive(ttl)));
    }

    private void write(String key, V value, Optional<Duration> ttl) {
        String full = fullKey(key);
        byte[] bytes = encode(value);
        execute("put", key, true, Mode.BEST_EFFORT, () -> null, () -> {
            driver.set(full, bytes, ttl);
            statistics.put(1);
            return null;
        });
    }

    @Override
    public boolean putIfAbsent(String key, V value) {
        return putIfAbsent(key, value, config.defaultTtl());
    }

    @Override
    public boolean putIfAbsent(String key, V value, Duration ttl) {
        return putIfAbsent(key, value, Optional.of(requirePositive(ttl)));
    }

    private boolean putIfAbsent(String key, V value, Optional<Duration> ttl) {
        String full = fullKey(key);
        byte[] bytes = encode(value);
        boolean stored = execute("putIfAbsent", key, false, Mode.STRICT, null, () -> driver.setIfAbsent(full, bytes, ttl));
        if (stored) {
            statistics.put(1);
        }
        return stored;
    }

    @Override
    public void putAll(Map<String, ? extends V> entries) {
        putAll(entries, config.defaultTtl());
    }

    @Override
    public void putAll(Map<String, ? extends V> entries, Duration ttl) {
        putAll(entries, Optional.of(requirePositive(ttl)));
    }

    private void putAll(Map<String, ? extends V> entries, Optional<Duration> ttl) {
        if (entries.isEmpty()) {
            return;
        }
        Map<String, byte[]> encoded = new LinkedHashMap<>();
        entries.forEach((key, value) -> encoded.put(fullKey(key), encode(value)));
        execute("putAll", "[" + entries.size() + " keys]", true, Mode.BEST_EFFORT, () -> null, () -> {
            driver.setAll(encoded, ttl);
            statistics.put(encoded.size());
            return null;
        });
    }

    @Override
    public boolean update(String key, V value) {
        String full = fullKey(key);
        byte[] bytes = encode(value);
        boolean updated = execute("update", key, true, Mode.BEST_EFFORT, () -> false, () -> driver.setIfPresent(full, bytes));
        if (updated) {
            statistics.put(1);
        }
        return updated;
    }

    @Override
    public boolean replace(String key, V expected, V value) {
        String full = fullKey(key);
        byte[] expectedBytes = encode(expected);
        byte[] bytes = encode(value);
        boolean replaced = execute("replace", key, false, Mode.STRICT, null,
                () -> driver.replaceIfEquals(full, expectedBytes, bytes));
        if (replaced) {
            statistics.put(1);
        }
        return replaced;
    }

    // --- deletes -------------------------------------------------------------------------------

    @Override
    public boolean delete(String key) {
        String full = fullKey(key);
        long removed = execute("delete", key, true, Mode.BEST_EFFORT, () -> 0L, () -> driver.delete(List.of(full)));
        statistics.evicted(removed);
        return removed > 0;
    }

    @Override
    public long deleteAll(Collection<String> keys) {
        List<String> full = keys.stream().distinct().map(this::fullKey).toList();
        if (full.isEmpty()) {
            return 0;
        }
        long removed = execute("deleteAll", "[" + full.size() + " keys]", true, Mode.BEST_EFFORT, () -> 0L,
                () -> driver.delete(full));
        statistics.evicted(removed);
        return removed;
    }

    @Override
    public Optional<V> getAndDelete(String key) {
        String full = fullKey(key);
        Optional<byte[]> value = execute("getAndDelete", key, false, Mode.STRICT, null, () -> driver.getAndDelete(full));
        countLookup(value.isPresent());
        value.ifPresent(v -> statistics.evicted(1));
        return value.map(codec::decode);
    }

    @Override
    public boolean deleteIfEquals(String key, V expected) {
        String full = fullKey(key);
        byte[] bytes = encode(expected);
        boolean deleted = execute("deleteIfEquals", key, false, Mode.STRICT, null, () -> driver.deleteIfEquals(full, bytes));
        if (deleted) {
            statistics.evicted(1);
        }
        return deleted;
    }

    @Override
    public long clear() {
        if (prefix.isEmpty()) {
            throw new IllegalStateException("clear() needs a namespace: without one it would delete every key on the server");
        }
        long removed = execute("clear", "*", true, Mode.BEST_EFFORT, () -> 0L, () -> driver.deleteByPrefix(prefix));
        statistics.evicted(removed);
        return removed;
    }

    // --- expiry and counters -------------------------------------------------------------------

    @Override
    public boolean expire(String key, Duration ttl) {
        String full = fullKey(key);
        Duration positive = requirePositive(ttl);
        return execute("expire", key, true, Mode.BEST_EFFORT, () -> false, () -> driver.expire(full, positive));
    }

    @Override
    public long increment(String key, long delta) {
        String full = fullKey(key);
        return execute("increment", key, false, Mode.STRICT, null, () -> driver.increment(full, delta));
    }

    @Override
    public long increment(String key, long delta, Duration ttl) {
        String full = fullKey(key);
        Duration positive = requirePositive(ttl);
        return execute("increment", key, false, Mode.STRICT, null, () -> driver.increment(full, delta, positive));
    }

    // --- cache-aside ---------------------------------------------------------------------------

    @Override
    public V getOrLoad(String key, Supplier<? extends V> loader) {
        return getOrLoad(key, config.defaultTtl(), loader);
    }

    @Override
    public V getOrLoad(String key, Duration ttl, Supplier<? extends V> loader) {
        return getOrLoad(key, Optional.of(requirePositive(ttl)), loader);
    }

    private V getOrLoad(String key, Optional<Duration> ttl, Supplier<? extends V> loader) {
        Objects.requireNonNull(loader, "loader");
        Optional<V> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> inFlight = loading.putIfAbsent(key, mine);
        if (inFlight != null) {
            return await(inFlight);
        }
        try {
            // Re-check: another JVM, or a load that finished just before ours started, may have filled it.
            V value = peek(key).orElseGet(() -> load(key, ttl, loader));
            mine.complete(value);
            return value;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(key, mine);
        }
    }

    /** A read that does not count towards hits and misses (the caller already counted the miss). */
    private Optional<V> peek(String key) {
        String full = fullKey(key);
        Optional<byte[]> raw = execute("get", key, true, Mode.BEST_EFFORT, Optional::empty, () -> driver.get(full));
        return raw.map(codec::decode);
    }

    private V load(String key, Optional<Duration> ttl, Supplier<? extends V> loader) {
        V loaded;
        try {
            loaded = loader.get();
        } catch (RuntimeException | Error e) {
            statistics.loadFailed();
            throw e;
        }
        statistics.loaded();
        if (loaded != null) {
            write(key, loaded, ttl);
        }
        return loaded;
    }

    private static <V> V await(CompletableFuture<V> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a concurrent load", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    // --- locks ---------------------------------------------------------------------------------

    @Override
    public Optional<CacheLock> tryLock(String name, Duration lease) {
        Duration positive = requirePositive(lease);
        String full = fullKey("lock:" + Objects.requireNonNull(name, "name"));
        byte[] token = UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII);
        boolean acquired = execute("tryLock", "lock:" + name, false, Mode.STRICT, null,
                () -> driver.setIfAbsent(full, token, Optional.of(positive)));
        return acquired ? Optional.of(new Lease(name, full, token)) : Optional.empty();
    }

    @Override
    public Optional<CacheLock> lock(String name, Duration lease, Duration wait) {
        Instant deadline = Instant.now().plus(Objects.requireNonNull(wait, "wait"));
        long backoff = 10;
        while (true) {
            Optional<CacheLock> lock = tryLock(name, lease);
            if (lock.isPresent() || !Instant.now().isBefore(deadline)) {
                return lock;
            }
            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(backoff / 2, backoff + 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            backoff = Math.min(backoff * 2, 250);
        }
    }

    private final class Lease implements CacheLock {
        private final String name;
        private final String key;
        private final byte[] token;

        Lease(String name, String key, byte[] token) {
            this.name = name;
            this.key = key;
            this.token = token;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean release() {
            return execute("unlock", "lock:" + name, false, Mode.STRICT, null, () -> driver.deleteIfEquals(key, token));
        }

        @Override
        public boolean extend(Duration lease) {
            Duration positive = requirePositive(lease);
            return execute("extendLock", "lock:" + name, false, Mode.STRICT, null,
                    () -> driver.expireIfEquals(key, token, positive));
        }
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(Objects.requireNonNull(nativeType, "nativeType"));
    }

    // --- plumbing ------------------------------------------------------------------------------

    static Duration requirePositive(Duration ttl) {
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isNegative() || ttl.isZero() || ttl.toMillis() == 0) {
            throw new IllegalArgumentException("TTL must be at least 1 ms: " + ttl);
        }
        return ttl;
    }

    private void countLookup(boolean hit) {
        if (hit) {
            statistics.hit(1);
        } else {
            statistics.miss(1);
        }
    }

    private String fullKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Cache key must not be empty");
        }
        return prefix + key;
    }

    private byte[] encode(V value) {
        return codec.encode(Objects.requireNonNull(value, "value"));
    }

    private <T> T execute(String operation, String key, boolean idempotent, Mode mode, Supplier<T> fallback,
                          Supplier<T> call) {
        boolean degradable = config.failOpen() && mode == Mode.BEST_EFFORT;
        if (degradable && !breaker.allowsCall()) {
            statistics.failOpenSkip();
            return fallback.get();
        }
        OperationContext context = new OperationContext(SERVICE, operation, provider, prefix + key, idempotent);
        try {
            T result = interceptors.execute(context, () -> {
                try {
                    return call.get();
                } catch (RuntimeException e) {
                    throw driver.translate(e);
                }
            });
            breaker.onSuccess();
            return result;
        } catch (CloudException e) {
            if (!e.isRetryable()) {
                throw e;
            }
            breaker.onFailure();
            if (!degradable) {
                throw e;
            }
            statistics.failOpenSkip();
            LOG.debug("Cache {} unavailable for {} (fail-open): {}", config.endpoint(), operation, e.getMessage());
            return fallback.get();
        }
    }
}
