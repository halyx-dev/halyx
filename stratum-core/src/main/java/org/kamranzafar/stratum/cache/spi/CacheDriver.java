package org.kamranzafar.stratum.cache.spi;

import org.kamranzafar.stratum.error.CloudException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);
}
