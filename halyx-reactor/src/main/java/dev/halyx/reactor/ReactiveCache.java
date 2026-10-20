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

import dev.halyx.cache.AsyncCache;
import dev.halyx.cache.Cache;
import dev.halyx.cache.CacheLock;
import dev.halyx.cache.CacheStatistics;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * A {@link Cache} as {@code Mono}s: each call runs when subscribed, once per subscription, on the
 * application's scheduler. A missing key is an empty {@code Mono}.
 *
 * @param <V> the value type, as the cache's codec reads and writes it
 */
public final class ReactiveCache<V> {

    private final Cache<V> cache;
    private final Calls calls;

    ReactiveCache(Cache<V> cache, Calls calls) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.calls = calls;
    }

    public String provider() {
        return cache.provider();
    }

    public String namespace() {
        return cache.namespace();
    }

    public CacheStatistics statistics() {
        return cache.statistics();
    }

    /** The blocking cache this wraps. */
    public Cache<V> blocking() {
        return cache;
    }

    /**
     * The cache's asynchronous view for one call, whose blocking work (where the driver has no non-blocking path)
     * runs on {@code blocking}: the scheduler, with the subscriber's context restored.
     */
    private AsyncCache<V> async(Executor blocking) {
        AsyncCache<V> view = cache.async(blocking);
        return view != null ? view : AsyncCache.onExecutor(cache, blocking); // a mock or proxy without one
    }

    public Mono<V> get(String key) {
        return calls.future(blocking -> async(blocking).get(key)).flatMap(Mono::justOrEmpty);
    }

    public Mono<Map<String, V>> getAll(Collection<String> keys) {
        return calls.future(blocking -> async(blocking).getAll(keys));
    }

    /** Completes when the server answers, even on a fail-open cache; see {@link Cache#ping()}. */
    public Mono<Void> ping() {
        return calls.run(cache::ping);
    }

    public Mono<Void> put(String key, V value) {
        return calls.future(blocking -> async(blocking).put(key, value)).then();
    }

    public Mono<Void> put(String key, V value, Duration ttl) {
        return calls.future(blocking -> async(blocking).put(key, value, ttl)).then();
    }

    public Mono<Boolean> putIfAbsent(String key, V value) {
        return calls.mono(() -> cache.putIfAbsent(key, value));
    }

    public Mono<Boolean> putIfAbsent(String key, V value, Duration ttl) {
        return calls.mono(() -> cache.putIfAbsent(key, value, ttl));
    }

    public Mono<Void> putAll(Map<String, ? extends V> entries) {
        return calls.run(() -> cache.putAll(entries));
    }

    public Mono<Void> putAll(Map<String, ? extends V> entries, Duration ttl) {
        return calls.run(() -> cache.putAll(entries, ttl));
    }

    /**
     * The cached value, or {@code loader}'s, stored for next time; concurrent loads of one key in this JVM
     * share one, as {@link Cache#getOrLoad} does. The loader is subscribed when needed, and nothing waits for it.
     * A loader that completes empty gives an empty result, and nothing is cached.
     */
    public Mono<V> getOrLoad(String key, Mono<? extends V> loader) {
        return calls.future(blocking -> async(blocking).getOrLoad(key, loader::toFuture));
    }

    public Mono<V> getOrLoad(String key, Duration ttl, Mono<? extends V> loader) {
        return calls.future(blocking -> async(blocking).getOrLoad(key, ttl, loader::toFuture));
    }

    public Mono<Boolean> delete(String key) {
        return calls.future(blocking -> async(blocking).delete(key));
    }

    public Mono<Long> deleteAll(Collection<String> keys) {
        return calls.mono(() -> cache.deleteAll(keys));
    }

    public Mono<Boolean> exists(String key) {
        return calls.future(blocking -> async(blocking).exists(key));
    }

    /** The remaining time to live, or empty if the key is missing or never expires. */
    public Mono<Duration> ttl(String key) {
        return calls.optional(() -> cache.ttl(key));
    }

    public Mono<Boolean> expire(String key, Duration ttl) {
        return calls.mono(() -> cache.expire(key, ttl));
    }

    public Mono<Long> increment(String key, long delta) {
        return calls.mono(() -> cache.increment(key, delta));
    }

    public Mono<Long> increment(String key, long delta, Duration ttl) {
        return calls.mono(() -> cache.increment(key, delta, ttl));
    }

    public Mono<Boolean> update(String key, V value) {
        return calls.mono(() -> cache.update(key, value));
    }

    public Mono<Boolean> replace(String key, V expected, V value) {
        return calls.mono(() -> cache.replace(key, expected, value));
    }

    public Mono<V> getAndDelete(String key) {
        return calls.optional(() -> cache.getAndDelete(key));
    }

    public Mono<Boolean> deleteIfEquals(String key, V expected) {
        return calls.mono(() -> cache.deleteIfEquals(key, expected));
    }

    public Mono<Long> clear() {
        return calls.mono(cache::clear);
    }

    /** The lock if it was free, or empty. */
    public Mono<CacheLock> tryLock(String name, Duration lease) {
        return calls.optional(() -> cache.tryLock(name, lease));
    }

    /** The lock, waiting up to {@code wait} on the scheduler's thread for it, or empty. */
    public Mono<CacheLock> lock(String name, Duration lease, Duration wait) {
        return calls.optional(() -> cache.lock(name, lease, wait));
    }

    /** False if the lock had already expired or been taken by another holder. */
    public Mono<Boolean> release(CacheLock lock) {
        return calls.mono(lock::release);
    }

    public Mono<Boolean> extend(CacheLock lock, Duration lease) {
        return calls.mono(() -> lock.extend(lease));
    }

    /**
     * Runs {@code work} holding the lock, and releases it however the work ends, including cancellation.
     * Empty, without running the work, if the lock wasn't acquired within {@code wait}. After a cancellation the
     * release runs asynchronously, shortly after the cancel returns, since cancelling can't wait for it.
     */
    public <T> Mono<T> withLock(String name, Duration lease, Duration wait, Function<CacheLock, Mono<T>> work) {
        return Mono.defer(() -> {
            // A cancellation can land after the scheduler's thread has taken the lock but before the lock reaches
            // usingWhen, which then has nothing to release: the acquisition hands it back itself.
            Acquisition acquisition = new Acquisition();
            Mono<CacheLock> acquire = calls.optional(() -> acquisition.keep(cache.lock(name, lease, wait)))
                    .doOnCancel(() -> acquisition.cancel().ifPresent(lock -> release(lock).subscribe(released -> { },
                            error -> { })));
            return Mono.usingWhen(acquire, work, this::release, (lock, error) -> release(lock), this::release);
        });
    }

    /** A lock being acquired for {@link #withLock}: whichever of arrival and cancellation comes second releases it. */
    private static final class Acquisition {

        private CacheLock lock;
        private boolean cancelled;

        /** On the scheduler's thread, with the lock just acquired: kept, or released at once if already cancelled. */
        synchronized Optional<CacheLock> keep(Optional<CacheLock> acquired) {
            if (cancelled) {
                acquired.ifPresent(CacheLock::release);
                return Optional.empty();
            }
            lock = acquired.orElse(null);
            return acquired;
        }

        /** The lock to release, if it arrived before the cancellation and so may never reach usingWhen. */
        synchronized Optional<CacheLock> cancel() {
            cancelled = true;
            Optional<CacheLock> held = Optional.ofNullable(lock);
            lock = null;
            return held;
        }
    }

    public <T> T unwrap(Class<T> nativeType) {
        return cache.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveCache[" + cache + "]";
    }
}
