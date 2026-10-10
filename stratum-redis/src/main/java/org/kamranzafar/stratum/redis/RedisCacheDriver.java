package org.kamranzafar.stratum.redis;

import io.lettuce.core.KeyValue;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisStringAsyncCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import org.kamranzafar.stratum.cache.spi.AbstractCacheDriver;
import org.kamranzafar.stratum.error.CloudException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Cache driver over one Lettuce connection, standalone or cluster. Multi-key commands are split
 * across hash slots by Lettuce's cluster client, so they work in both modes.
 */
final class RedisCacheDriver extends AbstractCacheDriver {

    private final RedisStringCommands<byte[], byte[]> strings;
    private final RedisKeyCommands<byte[], byte[]> keys;
    private final RedisStringAsyncCommands<byte[], byte[]> asyncStrings;
    private final Duration timeout;
    private final List<Object> nativeClients;

    RedisCacheDriver(String provider, RedisStringCommands<byte[], byte[]> strings, RedisKeyCommands<byte[], byte[]> keys,
                     RedisStringAsyncCommands<byte[], byte[]> asyncStrings, Duration timeout, List<Object> nativeClients) {
        super(provider);
        this.strings = strings;
        this.keys = keys;
        this.asyncStrings = asyncStrings;
        this.timeout = timeout;
        this.nativeClients = nativeClients;
    }

    @Override
    public Optional<byte[]> get(String key) {
        return Optional.ofNullable(strings.get(bytes(key)));
    }

    @Override
    public Map<String, byte[]> getAll(List<String> keyList) {
        List<KeyValue<byte[], byte[]>> values = strings.mget(keyList.stream().map(RedisCacheDriver::bytes).toArray(byte[][]::new));
        Map<String, byte[]> found = new LinkedHashMap<>();
        for (KeyValue<byte[], byte[]> kv : values) {
            if (kv.hasValue()) {
                found.put(new String(kv.getKey(), StandardCharsets.UTF_8), kv.getValue());
            }
        }
        return found;
    }

    @Override
    public void set(String key, byte[] value, Optional<Duration> ttl) {
        if (ttl.isPresent()) {
            strings.set(bytes(key), value, SetArgs.Builder.px(ttl.get().toMillis()));
        } else {
            strings.set(bytes(key), value);
        }
    }

    @Override
    public boolean setIfAbsent(String key, byte[] value, Optional<Duration> ttl) {
        SetArgs args = SetArgs.Builder.nx();
        ttl.ifPresent(t -> args.px(t.toMillis()));
        return "OK".equals(strings.set(bytes(key), value, args));
    }

    @Override
    public void setAll(Map<String, byte[]> entries, Optional<Duration> ttl) {
        if (ttl.isEmpty()) {
            Map<byte[], byte[]> values = new LinkedHashMap<>();
            entries.forEach((key, value) -> values.put(bytes(key), value));
            strings.mset(values);
            return;
        }
        // MSET cannot set expiry: pipeline one SET PX per key.
        SetArgs args = SetArgs.Builder.px(ttl.get().toMillis());
        List<RedisFuture<String>> futures = new ArrayList<>();
        entries.forEach((key, value) -> futures.add(asyncStrings.set(bytes(key), value, args)));
        LettuceFutures.awaitAll(timeout.toMillis(), TimeUnit.MILLISECONDS, futures.toArray(RedisFuture[]::new));
        for (RedisFuture<String> future : futures) {
            future.toCompletableFuture().join(); // surface any per-key failure
        }
    }

    @Override
    public long delete(List<String> keyList) {
        return keys.del(keyList.stream().map(RedisCacheDriver::bytes).toArray(byte[][]::new));
    }

    @Override
    public boolean exists(String key) {
        return keys.exists(bytes(key)) > 0;
    }

    @Override
    public Optional<Duration> ttl(String key) {
        long millis = keys.pttl(bytes(key));
        return millis < 0 ? Optional.empty() : Optional.of(Duration.ofMillis(millis)); // -2 missing, -1 no expiry
    }

    @Override
    public boolean expire(String key, Duration ttl) {
        return Boolean.TRUE.equals(keys.pexpire(bytes(key), ttl.toMillis()));
    }

    @Override
    public long increment(String key, long delta) {
        return strings.incrby(bytes(key), delta);
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        return RedisErrors.translate(cause, provider);
    }

    @Override
    protected List<Object> nativeClients() {
        return nativeClients;
    }

    private static byte[] bytes(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }
}
