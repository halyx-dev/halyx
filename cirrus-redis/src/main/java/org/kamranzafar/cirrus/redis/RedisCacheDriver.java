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
package org.kamranzafar.cirrus.redis;

import io.lettuce.core.KeyValue;
import io.lettuce.core.LettuceFutures;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanIterator;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.async.RedisStringAsyncCommands;
import io.lettuce.core.api.sync.RedisKeyCommands;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import io.lettuce.core.api.sync.RedisStringCommands;
import org.kamranzafar.cirrus.cache.spi.AbstractCacheDriver;
import org.kamranzafar.cirrus.error.CloudException;

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
    private final RedisScriptingCommands<byte[], byte[]> scripts;
    private final Duration timeout;
    private final List<Object> nativeClients;

    /** Atomic compare-and-act scripts: each touches one key, so they work unchanged in cluster mode. */
    private static final String INCREMENT_WITH_TTL =
            "local v = redis.call('INCRBY', KEYS[1], ARGV[1]) "
            + "if redis.call('PTTL', KEYS[1]) == -1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end return v";
    private static final String REPLACE_IF_EQUALS =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('SET', KEYS[1], ARGV[2], 'KEEPTTL') return 1 end return 0";
    private static final String DELETE_IF_EQUALS =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0";
    private static final String EXPIRE_IF_EQUALS =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) end return 0";
    private static final int SCAN_BATCH = 500;

    RedisCacheDriver(String provider, RedisStringCommands<byte[], byte[]> strings, RedisKeyCommands<byte[], byte[]> keys,
                     RedisStringAsyncCommands<byte[], byte[]> asyncStrings, RedisScriptingCommands<byte[], byte[]> scripts,
                     Duration timeout, List<Object> nativeClients) {
        super(provider);
        this.strings = strings;
        this.keys = keys;
        this.asyncStrings = asyncStrings;
        this.scripts = scripts;
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
    public long increment(String key, long delta, Duration ttl) {
        Long value = scripts.eval(INCREMENT_WITH_TTL, ScriptOutputType.INTEGER, new byte[][] {bytes(key)},
                ascii(delta), ascii(ttl.toMillis()));
        return value;
    }

    @Override
    public boolean setIfPresent(String key, byte[] value) {
        return "OK".equals(strings.set(bytes(key), value, SetArgs.Builder.xx().keepttl()));
    }

    @Override
    public boolean replaceIfEquals(String key, byte[] expected, byte[] value) {
        return isOne(scripts.eval(REPLACE_IF_EQUALS, ScriptOutputType.INTEGER, new byte[][] {bytes(key)}, expected, value));
    }

    @Override
    public Optional<byte[]> getAndDelete(String key) {
        return Optional.ofNullable(strings.getdel(bytes(key)));
    }

    @Override
    public boolean deleteIfEquals(String key, byte[] expected) {
        return isOne(scripts.eval(DELETE_IF_EQUALS, ScriptOutputType.INTEGER, new byte[][] {bytes(key)}, expected));
    }

    @Override
    public boolean expireIfEquals(String key, byte[] expected, Duration ttl) {
        return isOne(scripts.eval(EXPIRE_IF_EQUALS, ScriptOutputType.INTEGER, new byte[][] {bytes(key)},
                expected, ascii(ttl.toMillis())));
    }

    @Override
    public long deleteByPrefix(String prefix) {
        // SCAN never blocks the server; in cluster mode Lettuce walks every master. UNLINK frees memory
        // in the background, and batches are split across hash slots by the cluster client.
        ScanArgs args = ScanArgs.Builder.matches(bytes(globEscape(prefix) + "*")).limit(SCAN_BATCH);
        List<byte[]> batch = new ArrayList<>(SCAN_BATCH);
        long removed = 0;
        for (ScanIterator<byte[]> it = ScanIterator.scan(keys, args); it.hasNext(); ) {
            batch.add(it.next());
            if (batch.size() == SCAN_BATCH) {
                removed += keys.unlink(batch.toArray(byte[][]::new));
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            removed += keys.unlink(batch.toArray(byte[][]::new));
        }
        return removed;
    }

    /** Escapes Redis glob metacharacters so a namespace is matched literally. */
    static String globEscape(String literal) {
        StringBuilder escaped = new StringBuilder(literal.length() + 8);
        for (char c : literal.toCharArray()) {
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static boolean isOne(Long result) {
        return result != null && result == 1L;
    }

    private static byte[] ascii(long number) {
        return Long.toString(number).getBytes(StandardCharsets.US_ASCII);
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
