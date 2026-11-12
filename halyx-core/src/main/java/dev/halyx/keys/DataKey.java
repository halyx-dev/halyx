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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A 256-bit data key and its wrapped form, for applications with their own format, or for sealing several messages
 * with one key service call ({@link #seal}). Close it as soon as the key is no longer needed: closing zeroes the
 * plaintext held here (copies returned by {@link #plaintext()} are the caller's to clear). Not thread-safe once closed.
 */
public final class DataKey implements AutoCloseable {

    private final byte[] plaintext;
    private final WrappedKey wrapped;
    private volatile boolean closed;

    DataKey(byte[] plaintext, WrappedKey wrapped) {
        this.plaintext = Objects.requireNonNull(plaintext, "plaintext");
        this.wrapped = Objects.requireNonNull(wrapped, "wrapped");
    }

    /** A copy of the 32-byte key. */
    public byte[] plaintext() {
        return plaintextUnsafe().clone();
    }

    /** The key as wrapped by the key service: what to store next to the data. */
    public WrappedKey wrapped() {
        return wrapped;
    }

    /**
     * Seals {@code plaintext}, bound to {@code aad} (may be empty), with this data key: HLXS, with this key's wrapped
     * form as the only key entry. No key service call. Each message gets its own salt and nonce prefix, so its own
     * subkey: messages sealed with one data key stay independent, and each opens with {@link CryptoKey#decrypt} or
     * {@link KeyStore#decrypt} like any other.
     */
    public SealedData seal(byte[] plaintext, byte[] aad) {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(aad, "aad");
        return SealedData.of(Hlxs.seal(plaintextUnsafe(), List.of(wrapped), plaintext, aad,
                KeyStoreConfig.DEFAULT_SEGMENT_SIZE, Hlxs.randomSalt(), Hlxs.randomNoncePrefix()));
    }

    /**
     * Opens data sealed with this data key, with no key service call. Throws {@code InvalidArgumentException} if
     * {@code sealed} doesn't list this key's wrapped form, and {@code DataIntegrityException} for wrong {@code aad} or
     * changed data.
     */
    public byte[] open(SealedData sealed, byte[] aad) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(aad, "aad");
        if (!sealed.keys().contains(wrapped)) {
            throw new InvalidArgumentException("The data isn't sealed with this data key: it's sealed under "
                    + sealed.keys().stream().map(WrappedKey::keyId).toList());
        }
        return Hlxs.open(plaintextUnsafe(), sealed.header(), sealed.bytesUnsafe(), aad);
    }

    byte[] plaintextUnsafe() {
        if (closed) {
            throw new IllegalStateException("The data key is closed");
        }
        return plaintext;
    }

    /** Zeroes the plaintext key. */
    @Override
    public void close() {
        closed = true;
        Arrays.fill(plaintext, (byte) 0);
    }

    /** Never shows the key. */
    @Override
    public String toString() {
        return "DataKey[" + wrapped.keyId() + (closed ? ", closed" : "") + "]";
    }
}
