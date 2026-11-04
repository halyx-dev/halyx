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

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * A data key as wrapped by one key: the key's provider-prefixed id (such as {@code aws-kms:arn:aws:kms:...}), the key
 * version that wrapped it where the provider reports one, how it was wrapped, and the wrapped bytes. Not secret.
 */
public final class WrappedKey {

    private final String keyId;
    private final String version;
    private final int algorithmId;
    private final byte[] bytes;

    public WrappedKey(String keyId, Optional<String> version, WrapAlgorithm algorithm, byte[] bytes) {
        this(keyId, version.orElse(null), Objects.requireNonNull(algorithm, "algorithm").id(), bytes);
    }

    WrappedKey(String keyId, String version, int algorithmId, byte[] bytes) {
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        if (keyId.isEmpty() || keyId.indexOf(':') <= 0) {
            throw new IllegalArgumentException("A key id starts with its provider scheme, such as aws-kms: (got '"
                    + keyId + "')");
        }
        this.version = version == null || version.isEmpty() ? null : version;
        this.algorithmId = algorithmId;
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        if (bytes.length == 0 || bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("A wrapped key is 1 to 65535 bytes (got " + bytes.length + ")");
        }
    }

    /** The provider-prefixed id of the key that wrapped this data key. */
    public String keyId() {
        return keyId;
    }

    /** The scheme of {@link #keyId()}, such as {@code aws-kms}. */
    public String scheme() {
        return keyId.substring(0, keyId.indexOf(':'));
    }

    public Optional<String> version() {
        return Optional.ofNullable(version);
    }

    /** How the data key was wrapped; empty for an algorithm this version of Halyx doesn't know. */
    public Optional<WrapAlgorithm> algorithm() {
        return WrapAlgorithm.byId(algorithmId);
    }

    /** The wrapped bytes, a copy. */
    public byte[] bytes() {
        return bytes.clone();
    }

    int algorithmId() {
        return algorithmId;
    }

    byte[] bytesUnsafe() {
        return bytes;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WrappedKey other && keyId.equals(other.keyId) && Objects.equals(version, other.version)
                && algorithmId == other.algorithmId && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(keyId, version, algorithmId, Arrays.hashCode(bytes));
    }

    @Override
    public String toString() {
        return "WrappedKey[" + keyId + (version == null ? "" : ", version " + version) + "]";
    }
}
