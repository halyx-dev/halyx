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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The opt-in decrypt cache: unwrapped data keys by the digest of their wrapped form, least recently used first out.
 * Hands out copies, and zeroes keys when they expire, are evicted or the cache is cleared.
 */
final class DataKeyCacheMap {

    private record Entry(byte[] dek, long expiresAtNanos) {
    }

    private final long ttlNanos;
    private final int maxEntries;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

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
    synchronized byte[] get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (System.nanoTime() - entry.expiresAtNanos() > 0) {
            entries.remove(key);
            Arrays.fill(entry.dek(), (byte) 0);
            return null;
        }
        return entry.dek().clone();
    }

    synchronized void put(String key, byte[] dek) {
        Entry previous = entries.put(key, new Entry(dek.clone(), System.nanoTime() + ttlNanos));
        if (previous != null) {
            Arrays.fill(previous.dek(), (byte) 0);
        }
        Iterator<Map.Entry<String, Entry>> eldest = entries.entrySet().iterator();
        while (entries.size() > maxEntries && eldest.hasNext()) {
            Arrays.fill(eldest.next().getValue().dek(), (byte) 0);
            eldest.remove();
        }
    }

    synchronized void clear() {
        entries.values().forEach(entry -> Arrays.fill(entry.dek(), (byte) 0));
        entries.clear();
    }
}
