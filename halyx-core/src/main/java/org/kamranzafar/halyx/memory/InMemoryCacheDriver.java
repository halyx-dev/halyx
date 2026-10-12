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
package org.kamranzafar.halyx.memory;

import org.kamranzafar.halyx.cache.spi.AbstractCacheDriver;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.error.InvalidArgumentException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** An in-JVM cache with Redis semantics (lazy expiry, counters as decimal strings). */
final class InMemoryCacheDriver extends AbstractCacheDriver {

    private record Entry(byte[] value, long expiresAtNanos) {
        static final long NEVER = Long.MAX_VALUE;

        boolean expired(long now) {
            return expiresAtNanos != NEVER && now - expiresAtNanos >= 0;
        }
    }

    private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

    InMemoryCacheDriver() {
        super(InMemoryProvider.ID);
    }

    @Override
    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(live(key)).map(Entry::value);
    }

    @Override
    public Map<String, byte[]> getAll(List<String> keys) {
        Map<String, byte[]> found = new LinkedHashMap<>();
        for (String key : keys) {
            Entry entry = live(key);
            if (entry != null) {
                found.put(key, entry.value());
            }
        }
        return found;
    }

    @Override
    public void set(String key, byte[] value, Optional<Duration> ttl) {
        entries.put(key, new Entry(value.clone(), expiry(ttl)));
    }

    @Override
    public boolean setIfAbsent(String key, byte[] value, Optional<Duration> ttl) {
        Entry fresh = new Entry(value.clone(), expiry(ttl));
        boolean[] stored = {false};
        entries.compute(key, (k, existing) -> {
            if (existing == null || existing.expired(System.nanoTime())) {
                stored[0] = true;
                return fresh;
            }
            return existing;
        });
        return stored[0];
    }

    @Override
    public void setAll(Map<String, byte[]> values, Optional<Duration> ttl) {
        values.forEach((key, value) -> set(key, value, ttl));
    }

    @Override
    public long delete(List<String> keys) {
        long removed = 0;
        for (String key : keys) {
            Entry entry = entries.remove(key);
            if (entry != null && !entry.expired(System.nanoTime())) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    public boolean exists(String key) {
        return live(key) != null;
    }

    @Override
    public Optional<Duration> ttl(String key) {
        Entry entry = live(key);
        if (entry == null || entry.expiresAtNanos() == Entry.NEVER) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofNanos(Math.max(0, entry.expiresAtNanos() - System.nanoTime())));
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        boolean[] found = {false};
        entries.computeIfPresent(key, (k, existing) -> {
            if (existing.expired(System.nanoTime())) {
                return null;
            }
            found[0] = true;
            return new Entry(existing.value(), expiry(Optional.of(ttl)));
        });
        return found[0];
    }

    @Override
    public long increment(String key, long delta) {
        long[] result = new long[1];
        entries.compute(key, (k, existing) -> {
            boolean present = existing != null && !existing.expired(System.nanoTime());
            long current = present ? parse(existing.value()) : 0;
            result[0] = Math.addExact(current, delta);
            byte[] encoded = Long.toString(result[0]).getBytes(StandardCharsets.US_ASCII);
            return new Entry(encoded, present ? existing.expiresAtNanos() : Entry.NEVER);
        });
        return result[0];
    }

    @Override
    public long increment(String key, long delta, Duration ttl) {
        long[] result = new long[1];
        entries.compute(key, (k, existing) -> {
            boolean present = existing != null && !existing.expired(System.nanoTime());
            long current = present ? parse(existing.value()) : 0;
            result[0] = Math.addExact(current, delta);
            byte[] encoded = Long.toString(result[0]).getBytes(StandardCharsets.US_ASCII);
            long expiry = present && existing.expiresAtNanos() != Entry.NEVER ? existing.expiresAtNanos() : expiry(Optional.of(ttl));
            return new Entry(encoded, expiry);
        });
        return result[0];
    }

    @Override
    public boolean setIfPresent(String key, byte[] value) {
        boolean[] updated = {false};
        entries.computeIfPresent(key, (k, existing) -> {
            if (existing.expired(System.nanoTime())) {
                return null;
            }
            updated[0] = true;
            return new Entry(value.clone(), existing.expiresAtNanos());
        });
        return updated[0];
    }

    @Override
    public boolean replaceIfEquals(String key, byte[] expected, byte[] value) {
        boolean[] replaced = {false};
        entries.computeIfPresent(key, (k, existing) -> {
            if (existing.expired(System.nanoTime())) {
                return null;
            }
            if (Arrays.equals(existing.value(), expected)) {
                replaced[0] = true;
                return new Entry(value.clone(), existing.expiresAtNanos());
            }
            return existing;
        });
        return replaced[0];
    }

    @Override
    public Optional<byte[]> getAndDelete(String key) {
        Entry entry = entries.remove(key);
        return entry == null || entry.expired(System.nanoTime()) ? Optional.empty() : Optional.of(entry.value());
    }

    @Override
    public boolean deleteIfEquals(String key, byte[] expected) {
        boolean[] deleted = {false};
        entries.computeIfPresent(key, (k, existing) -> {
            if (existing.expired(System.nanoTime())) {
                return null;
            }
            if (Arrays.equals(existing.value(), expected)) {
                deleted[0] = true;
                return null;
            }
            return existing;
        });
        return deleted[0];
    }

    @Override
    public boolean expireIfEquals(String key, byte[] expected, Duration ttl) {
        boolean[] extended = {false};
        entries.computeIfPresent(key, (k, existing) -> {
            if (existing.expired(System.nanoTime())) {
                return null;
            }
            if (Arrays.equals(existing.value(), expected)) {
                extended[0] = true;
                return new Entry(existing.value(), expiry(Optional.of(ttl)));
            }
            return existing;
        });
        return extended[0];
    }

    @Override
    public long deleteByPrefix(String prefix) {
        long now = System.nanoTime();
        long removed = 0;
        for (var entry : entries.entrySet()) {
            if (entry.getKey().startsWith(prefix) && entries.remove(entry.getKey(), entry.getValue())
                    && !entry.getValue().expired(now)) {
                removed++;
            }
        }
        return removed;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        if (error instanceof ArithmeticException) {
            return Optional.of(new InvalidArgumentException("Counter overflow", error, provider, null));
        }
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of();
    }

    private Entry live(String key) {
        Entry entry = entries.get(key);
        if (entry != null && entry.expired(System.nanoTime())) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    private static long expiry(Optional<Duration> ttl) {
        return ttl.map(t -> System.nanoTime() + t.toNanos()).orElse(Entry.NEVER);
    }

    private long parse(byte[] value) {
        try {
            return Long.parseLong(new String(value, StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            throw new InvalidArgumentException("Value is not an integer counter", e, provider, null);
        }
    }
}
