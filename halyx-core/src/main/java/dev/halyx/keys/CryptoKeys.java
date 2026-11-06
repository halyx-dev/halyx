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

import dev.halyx.error.InvalidArgumentException;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Sealing for several keys at once, which may be on different providers and clouds: any one of them can then open
 * the data, with no rewrap. One data key is generated and wrapped by each key, so it costs one wrap call per key.
 *
 * <pre>{@code
 * SealedData sealed = CryptoKeys.encrypt(json, aad, awsKey, azureKey);
 * byte[] plain = azureKey.decrypt(sealed, aad);   // or awsKey.decrypt(...)
 * }</pre>
 */
public final class CryptoKeys {

    private CryptoKeys() {
    }

    /** Seals {@code plaintext} for every key in {@code keys}: 1 to 16 keys with distinct key ids. */
    public static SealedData encrypt(byte[] plaintext, byte[] aad, CryptoKey... keys) {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(aad, "aad");
        checkCount(keys);
        try (Sealing sealing = sealing(keys)) {
            return SealedData.of(Hlxs.seal(sealing.dek, sealing.entries, plaintext, aad, segmentSize(keys[0]),
                    Hlxs.randomSalt(), Hlxs.randomNoncePrefix()));
        }
    }

    /** A stream sealing what's written to it into {@code sink}, for every key in {@code keys}. */
    public static OutputStream encryptingStream(OutputStream sink, byte[] aad, CryptoKey... keys) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(aad, "aad");
        checkCount(keys);
        try (Sealing sealing = sealing(keys)) {
            return DefaultCryptoKey.sealingStream(sink, aad, sealing.dek, sealing.entries, segmentSize(keys[0]));
        }
    }

    private static void checkCount(CryptoKey[] keys) {
        Objects.requireNonNull(keys, "keys");
        if (keys.length == 0 || keys.length > Hlxs.MAX_KEYS) {
            throw new InvalidArgumentException("Sealed data has 1 to " + Hlxs.MAX_KEYS + " keys (got " + keys.length
                    + ")");
        }
        for (CryptoKey key : keys) {
            Objects.requireNonNull(key, "key");
        }
    }

    /** A data key and its entries, one per key in the caller's order; closing zeroes the data key. */
    private record Sealing(byte[] dek, List<WrappedKey> entries) implements AutoCloseable {
        @Override
        public void close() {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /**
     * A new data key wrapped by every key. With several keys of Halyx's own stores, the wraps run in parallel: sealing
     * for an AWS key and an Azure key then costs the slower round trip, not both. Each wrap starts on the calling thread
     * as an asynchronous call, so interceptors see the caller's context, such as its trace. The header refuses
     * duplicate key ids.
     */
    private static Sealing sealing(CryptoKey[] keys) {
        if (keys.length > 1 && Arrays.stream(keys).allMatch(DefaultCryptoKey.class::isInstance)) {
            byte[] dek = Hlxs.newDataKey();
            try {
                List<CompletableFuture<WrappedKey>> wraps = new ArrayList<>(keys.length);
                for (CryptoKey key : keys) {
                    wraps.add(((DefaultCryptoKey) key).wrapRawAsync(dek));
                }
                List<WrappedKey> entries = new ArrayList<>(keys.length);
                for (CompletableFuture<WrappedKey> wrap : wraps) {
                    entries.add(wrap.join());
                }
                return new Sealing(dek, entries);
            } catch (CompletionException e) {
                Arrays.fill(dek, (byte) 0);
                throw e.getCause() instanceof RuntimeException cause ? cause : e;
            } catch (RuntimeException | Error e) {
                Arrays.fill(dek, (byte) 0);
                throw e;
            }
        }
        DataKey dataKey = keys[0].generateDataKey();
        try {
            List<WrappedKey> entries = new ArrayList<>(keys.length);
            entries.add(dataKey.wrapped());
            for (int i = 1; i < keys.length; i++) {
                entries.add(keys[i].wrap(dataKey));
            }
            return new Sealing(dataKey.plaintextUnsafe().clone(), entries);
        } finally {
            dataKey.close();
        }
    }

    private static int segmentSize(CryptoKey first) {
        return first.store() instanceof DefaultKeyStore store ? store.config().segmentSize()
                : KeyStoreConfig.DEFAULT_SEGMENT_SIZE;
    }
}
