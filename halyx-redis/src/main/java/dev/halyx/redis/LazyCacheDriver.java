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
package dev.halyx.redis;

import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.interceptor.AsyncInvocation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Connects on first use, and again on a later call if connecting failed. An application can
 * therefore start while its cache is down (essential with fail-open), and recovers once the server
 * is back. After the first successful connect, Lettuce handles reconnects itself.
 *
 * <p>Concurrent callers share one connect attempt rather than queueing for their own, and for
 * {@link #RETRY_AFTER} after a failed attempt they fail at once with its error, so a cache that is down
 * costs callers one connect timeout at most, not one each. Waiting holds no monitor, which would pin a
 * virtual thread's carrier on Java 21.
 */
final class LazyCacheDriver implements CacheDriver {

    private final String provider;
    private final Supplier<CacheDriver> connector;
    /** How long a failed connect is reused before a call tries again. */
    static final Duration RETRY_AFTER = Duration.ofSeconds(1);

    private volatile CacheDriver delegate;
    /** The connect under way, or the last one, which failed if {@link #delegate} is still null. */
    private final AtomicReference<CompletableFuture<CacheDriver>> connecting = new AtomicReference<>();
    private volatile long failedAt;

    LazyCacheDriver(String provider, Supplier<CacheDriver> connector) {
        this.provider = provider;
        this.connector = connector;
    }

    /** The connected driver, connecting first on {@code blocking} if needed, so the caller never waits for it. */
    private CompletableFuture<CacheDriver> driverAsync(Executor blocking) {
        CacheDriver d = delegate;
        return d != null ? CompletableFuture.completedFuture(d) : CompletableFuture.supplyAsync(this::driver, blocking);
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getAsync(String key, Executor blocking) {
        return driverAsync(blocking).thenCompose(d -> d.getAsync(key, blocking));
    }

    @Override
    public CompletableFuture<Map<String, byte[]>> getAllAsync(List<String> keys, Executor blocking) {
        return driverAsync(blocking).thenCompose(d -> d.getAllAsync(keys, blocking));
    }

    @Override
    public CompletableFuture<Void> setAsync(String key, byte[] value, Optional<Duration> ttl, Executor blocking) {
        return driverAsync(blocking).thenCompose(d -> d.setAsync(key, value, ttl, blocking));
    }

    @Override
    public CompletableFuture<Long> deleteAsync(List<String> keys, Executor blocking) {
        return driverAsync(blocking).thenCompose(d -> d.deleteAsync(keys, blocking));
    }

    @Override
    public CompletableFuture<Boolean> existsAsync(String key, Executor blocking) {
        return driverAsync(blocking).thenCompose(d -> d.existsAsync(key, blocking));
    }

    private CacheDriver driver() {
        CacheDriver d = delegate;
        if (d != null) {
            return d;
        }
        while (true) {
            CompletableFuture<CacheDriver> current = connecting.get();
            if (current != null && (!current.isCompletedExceptionally()
                    || System.nanoTime() - failedAt < RETRY_AFTER.toNanos())) {
                return AsyncInvocation.join(current);
            }
            CompletableFuture<CacheDriver> mine = new CompletableFuture<>();
            if (connecting.compareAndSet(current, mine)) {
                try {
                    d = connector.get();
                } catch (RuntimeException | Error e) {
                    failedAt = System.nanoTime();
                    mine.completeExceptionally(e);
                    throw e;
                }
                delegate = d;
                mine.complete(d);
                return d;
            }
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        return driver().get(key);
    }

    @Override
    public Map<String, byte[]> getAll(List<String> keys) {
        return driver().getAll(keys);
    }

    @Override
    public void set(String key, byte[] value, Optional<Duration> ttl) {
        driver().set(key, value, ttl);
    }

    @Override
    public boolean setIfAbsent(String key, byte[] value, Optional<Duration> ttl) {
        return driver().setIfAbsent(key, value, ttl);
    }

    @Override
    public void setAll(Map<String, byte[]> entries, Optional<Duration> ttl) {
        driver().setAll(entries, ttl);
    }

    @Override
    public long delete(List<String> keys) {
        return driver().delete(keys);
    }

    @Override
    public boolean exists(String key) {
        return driver().exists(key);
    }

    @Override
    public Optional<Duration> ttl(String key) {
        return driver().ttl(key);
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return driver().expire(key, ttl);
    }

    @Override
    public long increment(String key, long delta) {
        return driver().increment(key, delta);
    }

    @Override
    public long increment(String key, long delta, Duration ttl) {
        return driver().increment(key, delta, ttl);
    }

    @Override
    public boolean setIfPresent(String key, byte[] value) {
        return driver().setIfPresent(key, value);
    }

    @Override
    public boolean replaceIfEquals(String key, byte[] expected, byte[] value) {
        return driver().replaceIfEquals(key, expected, value);
    }

    @Override
    public Optional<byte[]> getAndDelete(String key) {
        return driver().getAndDelete(key);
    }

    @Override
    public boolean deleteIfEquals(String key, byte[] expected) {
        return driver().deleteIfEquals(key, expected);
    }

    @Override
    public boolean expireIfEquals(String key, byte[] expected, Duration ttl) {
        return driver().expireIfEquals(key, expected, ttl);
    }

    @Override
    public long deleteByPrefix(String prefix) {
        return driver().deleteByPrefix(prefix);
    }

    @Override
    public CloudException translate(Throwable error) {
        CacheDriver d = delegate;
        if (error instanceof CloudException portable) {
            return portable;
        }
        return d != null ? d.translate(error) : RedisErrors.translate(error, provider).orElseGet(
                () -> new UnknownCloudException(error.getMessage(), error, provider, null));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver().unwrap(nativeType);
    }
}
