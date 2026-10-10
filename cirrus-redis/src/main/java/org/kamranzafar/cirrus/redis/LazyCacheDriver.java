package org.kamranzafar.cirrus.redis;

import org.kamranzafar.cirrus.cache.spi.CacheDriver;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.UnknownCloudException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Connects on first use, and again on a later call if connecting failed. An application can
 * therefore start while its cache is down (essential with fail-open), and recovers once the server
 * is back. After the first successful connect, Lettuce handles reconnects itself.
 */
final class LazyCacheDriver implements CacheDriver {

    private final String provider;
    private final Supplier<CacheDriver> connector;
    private volatile CacheDriver delegate;

    LazyCacheDriver(String provider, Supplier<CacheDriver> connector) {
        this.provider = provider;
        this.connector = connector;
    }

    private CacheDriver driver() {
        CacheDriver d = delegate;
        if (d == null) {
            synchronized (this) {
                d = delegate;
                if (d == null) {
                    d = connector.get();
                    delegate = d;
                }
            }
        }
        return d;
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
