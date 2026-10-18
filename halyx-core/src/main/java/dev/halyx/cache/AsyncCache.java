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

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The asynchronous view of a {@link Cache}, from {@link Cache#async()}: the cache-aside calls as
 * {@code CompletableFuture}s, with no thread waiting for the server where the driver is non-blocking. Fail-open,
 * statistics, interceptors and error translation are those of the blocking calls: on a fail-open cache, an
 * unavailable server gives a miss or skips the write, and the same circuit breaker spares it. Atomic operations,
 * counters and locks are on {@link #blocking()}.
 */
public interface AsyncCache<V> {

    String provider();

    String namespace();

    CompletableFuture<Optional<V>> get(String key);

    CompletableFuture<Map<String, V>> getAll(Collection<String> keys);

    /** Writes with the cache's default TTL, if it has one. */
    CompletableFuture<Void> put(String key, V value);

    CompletableFuture<Void> put(String key, V value, Duration ttl);

    /** True if there was a value. */
    CompletableFuture<Boolean> delete(String key);

    CompletableFuture<Boolean> exists(String key);

    /**
     * The cached value, or {@code loader}'s, stored with the default TTL. Concurrent loads of one key in this
     * process, blocking or not, share one call of a loader. A null value is returned but not stored.
     */
    CompletableFuture<V> getOrLoad(String key, Supplier<? extends CompletableFuture<? extends V>> loader);

    CompletableFuture<V> getOrLoad(String key, Duration ttl, Supplier<? extends CompletableFuture<? extends V>> loader);

    /** The blocking cache this is a view of. */
    Cache<V> blocking();

    /** A view that runs each of {@code cache}'s blocking calls on {@code executor}: for caches without one of their own. */
    static <V> AsyncCache<V> onExecutor(Cache<V> cache, Executor executor) {
        Objects.requireNonNull(cache, "cache");
        Objects.requireNonNull(executor, "executor");
        return new AsyncCache<>() {
            @Override
            public String provider() {
                return cache.provider();
            }

            @Override
            public String namespace() {
                return cache.namespace();
            }

            @Override
            public CompletableFuture<Optional<V>> get(String key) {
                return CompletableFuture.supplyAsync(() -> cache.get(key), executor);
            }

            @Override
            public CompletableFuture<Map<String, V>> getAll(Collection<String> keys) {
                return CompletableFuture.supplyAsync(() -> cache.getAll(keys), executor);
            }

            @Override
            public CompletableFuture<Void> put(String key, V value) {
                return CompletableFuture.runAsync(() -> cache.put(key, value), executor);
            }

            @Override
            public CompletableFuture<Void> put(String key, V value, Duration ttl) {
                return CompletableFuture.runAsync(() -> cache.put(key, value, ttl), executor);
            }

            @Override
            public CompletableFuture<Boolean> delete(String key) {
                return CompletableFuture.supplyAsync(() -> cache.delete(key), executor);
            }

            @Override
            public CompletableFuture<Boolean> exists(String key) {
                return CompletableFuture.supplyAsync(() -> cache.exists(key), executor);
            }

            @Override
            public CompletableFuture<V> getOrLoad(String key, Supplier<? extends CompletableFuture<? extends V>> loader) {
                return CompletableFuture.supplyAsync(() -> cache.getOrLoad(key, () -> loader.get().join()), executor);
            }

            @Override
            public CompletableFuture<V> getOrLoad(String key, Duration ttl,
                                                  Supplier<? extends CompletableFuture<? extends V>> loader) {
                return CompletableFuture.supplyAsync(() -> cache.getOrLoad(key, ttl, () -> loader.get().join()), executor);
            }

            @Override
            public Cache<V> blocking() {
                return cache;
            }
        };
    }
}
