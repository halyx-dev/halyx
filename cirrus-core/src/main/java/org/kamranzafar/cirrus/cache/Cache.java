package org.kamranzafar.cirrus.cache;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A portable key/value cache with expiry, backed by Redis or Valkey on every cloud (ElastiCache,
 * Azure Cache for Redis / Azure Managed Redis, Memorystore). Keys are strings; values are converted
 * with a {@link Codec}. Writes without a TTL use the cache's default TTL, if it has one.
 *
 * <p>Instances are thread-safe. Failures are reported as
 * {@link org.kamranzafar.cirrus.error.CloudException} subtypes.
 */
public interface Cache<V> {

    String provider();

    /** The key namespace, empty for none. */
    String namespace();

    Optional<V> get(String key);

    /** The values found; missing keys are absent from the result. */
    Map<String, V> getAll(Collection<String> keys);

    void put(String key, V value);

    void put(String key, V value, Duration ttl);

    /** Stores the value only if the key is absent. Returns whether it was stored. */
    boolean putIfAbsent(String key, V value);

    boolean putIfAbsent(String key, V value, Duration ttl);

    void putAll(Map<String, ? extends V> entries);

    void putAll(Map<String, ? extends V> entries, Duration ttl);

    /**
     * Returns the cached value, or loads, caches and returns it (cache-aside). Concurrent calls for
     * the same key in this JVM share one load. A {@code null} result is returned but not cached.
     */
    V getOrLoad(String key, Supplier<? extends V> loader);

    V getOrLoad(String key, Duration ttl, Supplier<? extends V> loader);

    /** Returns whether the key existed. */
    boolean delete(String key);

    /** Returns how many of the keys existed. */
    long deleteAll(Collection<String> keys);

    boolean exists(String key);

    /** Remaining time to live; empty if the key does not exist or never expires. */
    Optional<Duration> ttl(String key);

    /** Sets a new time to live. Returns false if the key does not exist. */
    boolean expire(String key, Duration ttl);

    /**
     * Atomically adds {@code delta} to a counter, creating it at zero, and returns the new value.
     * Counters are stored as decimal numbers, not with the codec: use {@code increment(key, 0)} to read one.
     */
    long increment(String key, long delta);

    /**
     * Like {@link #increment(String, long)}, and gives the counter {@code ttl} if it has no expiry yet,
     * in the same atomic step. This is the building block of fixed-window rate limits:
     *
     * <pre>{@code
     * long calls = cache.increment("rate:" + clientId + ":" + minute, 1, Duration.ofMinutes(1));
     * if (calls > 100) reject();
     * }</pre>
     */
    long increment(String key, long delta, Duration ttl);

    /** Replaces the value of an existing key, keeping its TTL. Returns false (and stores nothing) if absent. */
    boolean update(String key, V value);

    /** Atomically replaces the value only if it currently equals {@code expected}, keeping the TTL. */
    boolean replace(String key, V expected, V value);

    /** Atomically reads and removes a value (for one-time tokens). */
    Optional<V> getAndDelete(String key);

    /** Atomically deletes the key only if its value equals {@code expected}. */
    boolean deleteIfEquals(String key, V expected);

    /**
     * Deletes every key in this cache's namespace and returns how many. Uses incremental scanning, so
     * it does not block the server, but it is not atomic with concurrent writes.
     *
     * @throws IllegalStateException if the cache has no namespace, which would delete the whole server
     */
    long clear();

    /** Tries once to take the lock {@code name} for {@code lease}. */
    Optional<CacheLock> tryLock(String name, Duration lease);

    /** Waits up to {@code wait} for the lock {@code name}, retrying with backoff. */
    Optional<CacheLock> lock(String name, Duration lease, Duration wait);

    /** Hit, miss and load counters for this cache instance. */
    CacheStatistics statistics();

    /** Escape hatch to the native client, such as Lettuce's {@code StatefulRedisConnection}. */
    <T> T unwrap(Class<T> nativeType);
}
