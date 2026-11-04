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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Data sealed in the HLXS v1 envelope format: self-describing bytes that carry the wrapped data key for each key that
 * can open them, so any Halyx reader on any cloud can decrypt them with one of those keys. Parsing checks the
 * structure only; the keys and the authentication tags are checked when the data is opened. {@link #toString()} shows
 * the key ids, never bytes.
 */
public final class SealedData {

    private final byte[] bytes;
    private final Hlxs.Header header;

    private SealedData(byte[] bytes, Hlxs.Header header) {
        this.bytes = bytes;
        this.header = header;
    }

    static SealedData of(byte[] sealed) {
        return new SealedData(sealed, Hlxs.parseHeader(sealed));
    }

    /** Sealed data stored with {@link #toBytes()}; throws {@code InvalidArgumentException} if it isn't HLXS. */
    public static SealedData parse(byte[] stored) {
        return of(Objects.requireNonNull(stored, "stored").clone());
    }

    /** Sealed data stored with {@link #toText()}: {@code hlxs1:} followed by unpadded base64url. */
    public static SealedData parseText(String text) {
        Objects.requireNonNull(text, "text");
        if (!text.startsWith(Hlxs.TEXT_PREFIX)) {
            throw new InvalidArgumentException("Not HLXS text: it starts with '" + Hlxs.TEXT_PREFIX + "'");
        }
        try {
            return of(Base64.getUrlDecoder().decode(text.substring(Hlxs.TEXT_PREFIX.length())));
        } catch (IllegalArgumentException e) {
            throw new InvalidArgumentException("Malformed HLXS text: " + e.getMessage(), e, null, null);
        }
    }

    /** The sealed bytes, to store. */
    public byte[] toBytes() {
        return bytes.clone();
    }

    /** The sealed bytes as text, for configuration values: {@code hlxs1:} followed by unpadded base64url. */
    public String toText() {
        return Hlxs.TEXT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The wrapped data key for each key that can open this data, in header order. */
    public List<WrappedKey> keys() {
        return header.keys();
    }

    /** The HLXS format version: 1. */
    public int formatVersion() {
        return Hlxs.FORMAT_VERSION;
    }

    /**
     * This data without the entry for {@code keyId}, so that key can no longer open it. No key service call is made,
     * and the encrypted body is unchanged. The last entry can't be removed.
     */
    public SealedData withoutKey(String keyId) {
        Objects.requireNonNull(keyId, "keyId");
        List<WrappedKey> remaining = new ArrayList<>(header.keys());
        if (!remaining.removeIf(key -> key.keyId().equals(keyId))) {
            throw new InvalidArgumentException("The sealed data has no entry for key " + keyId);
        }
        if (remaining.isEmpty()) {
            throw new InvalidArgumentException("The last key entry can't be removed: nothing could open the data");
        }
        return withKeys(remaining);
    }

    /** The same body under a new key list; the commitment and subkey don't depend on it. */
    SealedData withKeys(List<WrappedKey> keys) {
        Hlxs.Header next = header.withKeys(keys);
        byte[] encoded = next.encode();
        byte[] out = new byte[encoded.length + bytes.length - header.length()];
        System.arraycopy(encoded, 0, out, 0, encoded.length);
        System.arraycopy(bytes, header.length(), out, encoded.length, bytes.length - header.length());
        return new SealedData(out, next);
    }

    Hlxs.Header header() {
        return header;
    }

    byte[] bytesUnsafe() {
        return bytes;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SealedData other && Arrays.equals(bytes, other.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "SealedData" + header.keys();
    }
}
