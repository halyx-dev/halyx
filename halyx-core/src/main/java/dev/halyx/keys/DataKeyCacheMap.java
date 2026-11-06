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
package dev.halyx.keys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The opt-in decrypt cache: unwrapped data keys by the digest of their wrapped form, least recently used first out.
 * Hands out copies, and zeroes keys when they expire, are evicted or the cache is cleared.
 *
 * <p>Hits take no shared lock, since every decryption of cached data goes through here. Each entry guards its own key,
 * so a key being zeroed is never handed out; finding the least recently used entry scans the map, but only when a
 * put overflows it, and a put follows a key service call that costs far more.
 */
final class DataKeyCacheMap {

    private static final class Entry {
        private final byte[] dek;
        private final long expiresAtNanos;
        private volatile long usedAtNanos;
        private boolean zeroed; // guarded by this entry

        Entry(byte[] dek, long expiresAtNanos) {
            this.dek = dek;
            this.expiresAtNanos = expiresAtNanos;
            this.usedAtNanos = System.nanoTime();
        }

        /** A copy of the key, or null once it's been zeroed. */
        synchronized byte[] copy() {
            if (zeroed) {
                return null;
            }
            usedAtNanos = System.nanoTime();
            return dek.clone();
        }

        synchronized void zero() {
            Arrays.fill(dek, (byte) 0);
            zeroed = true;
        }

        boolean expired(long now) {
            return now - expiresAtNanos > 0;
        }
    }

    private final long ttlNanos;
    private final int maxEntries;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    DataKeyCacheMap(KeyStoreConfig.DataKeyCache config) {
        this.ttlNanos = config.ttl().toNanos();
        this.maxEntries = config.maxEntries();
    }

    static String key(WrappedKey wrapped) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(wrapped.keyId().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(wrapped.version().orElse("").getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(wrapped.bytesUnsafe());
            return Base64.getEncoder().encodeToString(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** A copy of the cached data key, or null. */
    byte[] get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired(System.nanoTime())) {
            if (entries.remove(key, entry)) {
                entry.zero();
            }
            return null;
        }
        return entry.copy();
    }

    void put(String key, byte[] dek) {
        Entry previous = entries.put(key, new Entry(dek.clone(), System.nanoTime() + ttlNanos));
        if (previous != null) {
            previous.zero();
        }
        while (entries.size() > maxEntries) {
            evictOne();
        }
    }

    /** Evicts an expired entry if there is one, else the least recently used. */
    private void evictOne() {
        long now = System.nanoTime();
        Map.Entry<String, Entry> victim = null;
        for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
            if (candidate.getValue().expired(now)) {
                victim = candidate;
                break;
            }
            if (victim == null || candidate.getValue().usedAtNanos - victim.getValue().usedAtNanos < 0) {
                victim = candidate;
            }
        }
        if (victim != null && entries.remove(victim.getKey(), victim.getValue())) {
            victim.getValue().zero();
        }
    }

    void clear() {
        for (Map.Entry<String, Entry> entry : entries.entrySet()) {
            if (entries.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().zero();
            }
        }
    }
}
