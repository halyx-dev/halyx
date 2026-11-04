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
import java.util.List;
import java.util.Objects;

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
        try (DataKey dataKey = keys[0].generateDataKey()) {
            List<WrappedKey> entries = entries(dataKey, keys);
            return SealedData.of(Hlxs.seal(dataKey.plaintextUnsafe(), entries, plaintext, aad, segmentSize(keys[0]),
                    Hlxs.randomSalt(), Hlxs.randomNoncePrefix()));
        }
    }

    /** A stream sealing what's written to it into {@code sink}, for every key in {@code keys}. */
    public static OutputStream encryptingStream(OutputStream sink, byte[] aad, CryptoKey... keys) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(aad, "aad");
        checkCount(keys);
        try (DataKey dataKey = keys[0].generateDataKey()) {
            return DefaultCryptoKey.sealingStream(sink, aad, dataKey.plaintextUnsafe(), entries(dataKey, keys),
                    segmentSize(keys[0]));
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

    private static List<WrappedKey> entries(DataKey dataKey, CryptoKey[] keys) {
        List<WrappedKey> entries = new ArrayList<>(keys.length);
        entries.add(dataKey.wrapped());
        for (int i = 1; i < keys.length; i++) {
            entries.add(keys[i].wrap(dataKey));
        }
        return entries; // the header refuses duplicate key ids
    }

    private static int segmentSize(CryptoKey first) {
        return first.store() instanceof DefaultKeyStore store ? store.config().segmentSize()
                : KeyStoreConfig.DEFAULT_SEGMENT_SIZE;
    }
}
