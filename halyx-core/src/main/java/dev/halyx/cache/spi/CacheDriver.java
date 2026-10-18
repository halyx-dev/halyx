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
package dev.halyx.cache.spi;

import dev.halyx.error.CloudException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Provider SPI for caches. Works on full keys (namespace already applied) and raw bytes; the
 * portable client in core handles codecs, namespaces, defaults, validation and interceptors.
 * A driver is shared by every cache on the same endpoint.
 */
public interface CacheDriver {

    Optional<byte[]> get(String key);

    /** Values of the keys that exist. */
    Map<String, byte[]> getAll(List<String> keys);

    void set(String key, byte[] value, Optional<Duration> ttl);

    boolean setIfAbsent(String key, byte[] value, Optional<Duration> ttl);

    void setAll(Map<String, byte[]> entries, Optional<Duration> ttl);

    /** Returns how many keys existed. */
    long delete(List<String> keys);

    boolean exists(String key);

    Optional<Duration> ttl(String key);

    boolean expire(String key, Duration ttl);

    long increment(String key, long delta);

    /** INCRBY, then PEXPIRE if the key has no TTL, atomically. */
    long increment(String key, long delta, Duration ttl);

    /** SET XX KEEPTTL. */
    boolean setIfPresent(String key, byte[] value);

    /** Replace if the current value equals {@code expected}, keeping the TTL, atomically. */
    boolean replaceIfEquals(String key, byte[] expected, byte[] value);

    Optional<byte[]> getAndDelete(String key);

    boolean deleteIfEquals(String key, byte[] expected);

    /** PEXPIRE only if the current value equals {@code expected}, atomically. */
    boolean expireIfEquals(String key, byte[] expected, Duration ttl);

    /** Deletes every key starting with {@code prefix} (non-empty) and returns how many. */
    long deleteByPrefix(String prefix);

    // --- asynchronous calls ------------------------------------------------------------------------
    // Each defaults to its blocking call on the blocking executor; a driver whose client is non-blocking overrides
    // them. Failures may be returned or thrown; the API layer translates both.

    default CompletableFuture<Optional<byte[]>> getAsync(String key, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> get(key), blocking);
    }

    default CompletableFuture<Map<String, byte[]>> getAllAsync(List<String> keys, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> getAll(keys), blocking);
    }

    default CompletableFuture<Void> setAsync(String key, byte[] value, Optional<Duration> ttl, Executor blocking) {
        return CompletableFuture.runAsync(() -> set(key, value, ttl), blocking);
    }

    default CompletableFuture<Long> deleteAsync(List<String> keys, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> delete(keys), blocking);
    }

    default CompletableFuture<Boolean> existsAsync(String key, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> exists(key), blocking);
    }

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);
}
