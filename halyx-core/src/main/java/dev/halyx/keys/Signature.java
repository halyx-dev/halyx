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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * A signature, with the algorithm and the key version that made it, so it can be verified after the key is rotated.
 * {@link #bytes()} are what the algorithm defines (DER for ECDSA); {@link #toBytes()} adds the algorithm and version
 * for storage.
 */
public final class Signature {

    private static final byte[] MAGIC = {'H', 'L', 'S', 'G'};
    private static final int FORMAT = 1;

    private final byte[] bytes;
    private final SigningAlgorithm algorithm;
    private final String keyVersion;

    public Signature(byte[] bytes, SigningAlgorithm algorithm, Optional<String> keyVersion) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.keyVersion = Objects.requireNonNull(keyVersion, "keyVersion").filter(v -> !v.isEmpty()).orElse(null);
        if (bytes.length == 0 || bytes.length > 0xFFFF) {
            throw new IllegalArgumentException("A signature is 1 to 65535 bytes (got " + bytes.length + ")");
        }
    }

    /** The signature as the algorithm defines it, a copy. */
    public byte[] bytes() {
        return bytes.clone();
    }

    public SigningAlgorithm algorithm() {
        return algorithm;
    }

    /** The key version that signed, where the provider has versions. */
    public Optional<String> keyVersion() {
        return Optional.ofNullable(keyVersion);
    }

    /** The signature with its algorithm and key version, for storage: read it back with {@link #parse}. */
    public byte[] toBytes() {
        byte[] version = keyVersion == null ? new byte[0] : keyVersion.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + 1 + 1 + 2 + version.length + 2 + bytes.length)
                .put(MAGIC).put((byte) FORMAT).put((byte) algorithm.id())
                .putShort((short) version.length).put(version)
                .putShort((short) bytes.length).put(bytes)
                .array();
    }

    /** A signature stored with {@link #toBytes()}. */
    public static Signature parse(byte[] stored) {
        Objects.requireNonNull(stored, "stored");
        try {
            ByteBuffer in = ByteBuffer.wrap(stored);
            byte[] magic = new byte[4];
            in.get(magic);
            if (!Arrays.equals(magic, MAGIC) || in.get() != FORMAT) {
                throw new InvalidArgumentException("Not a Halyx signature, or an unknown signature format");
            }
            SigningAlgorithm algorithm = SigningAlgorithm.byId(in.get() & 0xFF);
            byte[] version = new byte[in.getShort() & 0xFFFF];
            in.get(version);
            byte[] signature = new byte[in.getShort() & 0xFFFF];
            in.get(signature);
            if (in.hasRemaining()) {
                throw new InvalidArgumentException("Trailing bytes after the signature");
            }
            return new Signature(signature, algorithm, Optional.of(new String(version, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            if (e instanceof InvalidArgumentException invalid) {
                throw invalid;
            }
            throw new InvalidArgumentException("Malformed signature: " + e.getMessage(), e, null, null);
        }
    }

    byte[] bytesUnsafe() {
        return bytes;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Signature other && Arrays.equals(bytes, other.bytes) && algorithm == other.algorithm
                && Objects.equals(keyVersion, other.keyVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(bytes), algorithm, keyVersion);
    }

    @Override
    public String toString() {
        return "Signature[" + algorithm + (keyVersion == null ? "" : ", version " + keyVersion) + "]";
    }
}
