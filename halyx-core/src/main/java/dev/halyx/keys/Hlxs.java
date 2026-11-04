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

import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The HLXS v1 envelope format (spec: {@code docs/api/keys-format.md}). A header carries the format, the segment size,
 * a salt, a nonce prefix, a key commitment and one to sixteen wrapped copies of the data key; the body is the
 * plaintext in segments, each sealed with AES-256-GCM under a subkey derived from the data key.
 *
 * <p>The subkey and the commitment are derived from the data key, the salt and the fixed prefix only, never from the
 * key entries, so rewrapping rewrites the header and leaves the body as it is. The commitment protects the entries: a
 * replaced entry unwraps to a different data key and fails the commitment check before anything is decrypted.
 */
final class Hlxs {

    static final byte[] MAGIC = {'H', 'L', 'X', 'S'};
    static final int FORMAT_VERSION = 1;
    /** AES-256-GCM in segments, HKDF-SHA256 subkey, key commitment. */
    static final int ALGORITHM = 1;
    static final int FIXED_PREFIX_LENGTH = 10;
    static final int SALT_LENGTH = 16;
    static final int NONCE_PREFIX_LENGTH = 7;
    static final int COMMITMENT_LENGTH = 32;
    static final int TAG_LENGTH = 16;
    static final int DEK_LENGTH = 32;
    static final int MAX_KEYS = 16;
    /** The largest segment readers accept; writers use {@link KeyStoreConfig#segmentSize()}. */
    static final int MAX_SEGMENT_SIZE = 16 * 1024 * 1024;
    static final String TEXT_PREFIX = "hlxs1:";

    private static final byte[] KEY_INFO = "HLXS1 key".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COMMIT_INFO = "HLXS1 commit".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Hlxs() {
    }

    /** The parsed header; {@code length} is its size in bytes. */
    record Header(int segmentSize, byte[] salt, byte[] noncePrefix, byte[] commitment, List<WrappedKey> keys,
                  int length) {

        byte[] fixedPrefix() {
            return Hlxs.fixedPrefix(segmentSize);
        }

        Header withKeys(List<WrappedKey> newKeys) {
            byte[] encoded = Hlxs.encode(segmentSize, salt, noncePrefix, commitment, newKeys);
            return new Header(segmentSize, salt, noncePrefix, commitment, List.copyOf(newKeys), encoded.length);
        }

        byte[] encode() {
            return Hlxs.encode(segmentSize, salt, noncePrefix, commitment, keys);
        }
    }

    static byte[] fixedPrefix(int segmentSize) {
        return ByteBuffer.allocate(FIXED_PREFIX_LENGTH).put(MAGIC).put((byte) FORMAT_VERSION).put((byte) ALGORITHM)
                .putInt(segmentSize).array();
    }

    static byte[] randomSalt() {
        return random(SALT_LENGTH);
    }

    static byte[] randomNoncePrefix() {
        return random(NONCE_PREFIX_LENGTH);
    }

    static byte[] newDataKey() {
        return random(DEK_LENGTH);
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    static byte[] commitment(byte[] dek, byte[] salt, int segmentSize) {
        return hkdf(dek, salt, concat(COMMIT_INFO, fixedPrefix(segmentSize)), COMMITMENT_LENGTH);
    }

    static byte[] subkey(byte[] dek, byte[] salt, int segmentSize) {
        return hkdf(dek, salt, concat(KEY_INFO, fixedPrefix(segmentSize)), DEK_LENGTH);
    }

    /** Throws {@link DataIntegrityException} unless {@code dek} is the data key this header was sealed with. */
    static void checkCommitment(byte[] dek, Header header) {
        byte[] expected = commitment(dek, header.salt(), header.segmentSize());
        if (!MessageDigest.isEqual(expected, header.commitment())) {
            throw new DataIntegrityException("The sealed data's key commitment doesn't match its data key: the key "
                    + "entries were changed, or the data was sealed under another key");
        }
    }

    /** HKDF-SHA256 (RFC 5869), for outputs up to 32 bytes. */
    static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        if (length > 32) {
            throw new IllegalArgumentException("HKDF output above 32 bytes isn't needed here");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt.length == 0 ? new byte[32] : salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            Arrays.fill(prk, (byte) 0);
            mac.update(info);
            mac.update((byte) 1);
            byte[] okm = mac.doFinal();
            byte[] out = Arrays.copyOf(okm, length);
            Arrays.fill(okm, (byte) 0);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    static byte[] encode(int segmentSize, byte[] salt, byte[] noncePrefix, byte[] commitment, List<WrappedKey> keys) {
        checkKeys(keys);
        ByteArrayOutputStream out = new ByteArrayOutputStream(128 + 128 * keys.size());
        out.writeBytes(fixedPrefix(segmentSize));
        out.writeBytes(salt);
        out.writeBytes(noncePrefix);
        out.writeBytes(commitment);
        out.write(keys.size());
        for (WrappedKey key : keys) {
            writeField(out, key.keyId().getBytes(StandardCharsets.UTF_8));
            writeField(out, key.version().orElse("").getBytes(StandardCharsets.UTF_8));
            out.write(key.algorithmId());
            writeField(out, key.bytesUnsafe());
        }
        return out.toByteArray();
    }

    private static void checkKeys(List<WrappedKey> keys) {
        if (keys.isEmpty() || keys.size() > MAX_KEYS) {
            throw new InvalidArgumentException("Sealed data has 1 to " + MAX_KEYS + " keys (got " + keys.size() + ")");
        }
        Set<String> ids = new HashSet<>();
        for (WrappedKey key : keys) {
            if (!ids.add(key.keyId())) {
                throw new InvalidArgumentException("Sealed data lists key " + key.keyId() + " more than once");
            }
        }
    }

    private static void writeField(ByteArrayOutputStream out, byte[] value) {
        if (value.length > 0xFFFF) {
            throw new InvalidArgumentException("A header field is at most 65535 bytes (got " + value.length + ")");
        }
        out.write(value.length >>> 8);
        out.write(value.length & 0xFF);
        out.writeBytes(value);
    }

    /** Parses the header at the start of {@code sealed}. */
    static Header parseHeader(byte[] sealed) {
        try {
            return parseHeader(new DataInputStream(new ByteArrayInputStream(sealed)));
        } catch (IOException e) {
            throw new IllegalStateException(e); // can't happen on a byte array
        }
    }

    /** Reads exactly the header from {@code in}. */
    static Header parseHeader(DataInputStream in) throws IOException {
        try {
            byte[] magic = new byte[MAGIC.length];
            in.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new InvalidArgumentException("Not HLXS sealed data");
            }
            int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new InvalidArgumentException("Unknown HLXS format version " + version
                        + "; this version of Halyx reads version " + FORMAT_VERSION);
            }
            int algorithm = in.readUnsignedByte();
            if (algorithm != ALGORITHM) {
                throw new InvalidArgumentException("Unknown HLXS algorithm " + algorithm);
            }
            int segmentSize = in.readInt();
            if (segmentSize <= 0 || segmentSize > MAX_SEGMENT_SIZE) {
                throw new InvalidArgumentException("HLXS segment size out of range: " + segmentSize);
            }
            byte[] salt = new byte[SALT_LENGTH];
            in.readFully(salt);
            byte[] noncePrefix = new byte[NONCE_PREFIX_LENGTH];
            in.readFully(noncePrefix);
            byte[] commitment = new byte[COMMITMENT_LENGTH];
            in.readFully(commitment);
            int count = in.readUnsignedByte();
            int length = FIXED_PREFIX_LENGTH + SALT_LENGTH + NONCE_PREFIX_LENGTH + COMMITMENT_LENGTH + 1;
            List<WrappedKey> keys = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                byte[] id = readField(in);
                byte[] keyVersion = readField(in);
                int wrapAlgorithm = in.readUnsignedByte();
                byte[] wrapped = readField(in);
                length += 2 + id.length + 2 + keyVersion.length + 1 + 2 + wrapped.length;
                try {
                    keys.add(new WrappedKey(new String(id, StandardCharsets.UTF_8),
                            new String(keyVersion, StandardCharsets.UTF_8), wrapAlgorithm, wrapped));
                } catch (IllegalArgumentException e) {
                    throw new InvalidArgumentException("Malformed HLXS key entry " + i + ": " + e.getMessage(), e,
                            null, null);
                }
            }
            checkKeys(keys);
            return new Header(segmentSize, salt, noncePrefix, commitment, List.copyOf(keys), length);
        } catch (EOFException e) {
            throw new DataIntegrityException("The HLXS header is truncated", e, null, null);
        }
    }

    private static byte[] readField(DataInputStream in) throws IOException {
        byte[] value = new byte[in.readUnsignedShort()];
        in.readFully(value);
        return value;
    }

    /** Seals {@code plaintext} in one go; the header comes from the arguments. */
    static byte[] seal(byte[] dek, List<WrappedKey> keys, byte[] plaintext, byte[] aad, int segmentSize, byte[] salt,
                       byte[] noncePrefix) {
        byte[] header = encode(segmentSize, salt, noncePrefix, commitment(dek, salt, segmentSize), keys);
        Segments segments = new Segments(dek, salt, noncePrefix, segmentSize, aad);
        int count = Math.max(1, (plaintext.length + segmentSize - 1) / segmentSize);
        ByteBuffer out = ByteBuffer.allocate(header.length + plaintext.length + count * TAG_LENGTH);
        out.put(header);
        for (int i = 0; i < count; i++) {
            int from = i * segmentSize;
            int length = Math.min(segmentSize, plaintext.length - from);
            out.put(segments.seal(i, i == count - 1, plaintext, from, length));
        }
        return out.array();
    }

    /** Opens the body of {@code sealed}, after checking {@code dek} against the commitment. */
    static byte[] open(byte[] dek, Header header, byte[] sealed, byte[] aad) {
        checkCommitment(dek, header);
        Segments segments = new Segments(dek, header.salt(), header.noncePrefix(), header.segmentSize(), aad);
        int full = header.segmentSize() + TAG_LENGTH;
        int body = sealed.length - header.length();
        if (body < TAG_LENGTH) {
            throw new DataIntegrityException("The sealed data is truncated: no final segment");
        }
        int count = (body + full - 1) / full;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(0, body - count * TAG_LENGTH));
        for (int i = 0; i < count; i++) {
            int from = header.length() + i * full;
            int length = Math.min(full, sealed.length - from);
            out.writeBytes(segments.open(i, i == count - 1, sealed, from, length));
        }
        return out.toByteArray();
    }

    /** The sealed size of {@code plaintextLength} bytes under a header of {@code headerLength}. */
    static long sealedLength(long plaintextLength, int headerLength, int segmentSize) {
        long count = Math.max(1, (plaintextLength + segmentSize - 1) / segmentSize);
        return headerLength + plaintextLength + count * TAG_LENGTH;
    }

    /** Segment encryption under one message's subkey. */
    static final class Segments {

        private final SecretKeySpec key;
        private final byte[] noncePrefix;
        private final byte[] aad;
        private final int segmentSize;

        Segments(byte[] dek, byte[] salt, byte[] noncePrefix, int segmentSize, byte[] callerAad) {
            byte[] subkey = subkey(dek, salt, segmentSize);
            this.key = new SecretKeySpec(subkey, "AES");
            Arrays.fill(subkey, (byte) 0);
            this.noncePrefix = noncePrefix.clone();
            this.segmentSize = segmentSize;
            this.aad = ByteBuffer.allocate(4 + callerAad.length).putInt(callerAad.length).put(callerAad).array();
        }

        int segmentSize() {
            return segmentSize;
        }

        byte[] seal(long index, boolean last, byte[] in, int offset, int length) {
            try {
                Cipher gcm = cipher(Cipher.ENCRYPT_MODE, index, last);
                return gcm.doFinal(in, offset, length);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AES-GCM failed", e);
            }
        }

        byte[] open(long index, boolean last, byte[] in, int offset, int length) {
            if (length < TAG_LENGTH) {
                throw new DataIntegrityException("Sealed segment " + index + " is truncated");
            }
            try {
                Cipher gcm = cipher(Cipher.DECRYPT_MODE, index, last);
                return gcm.doFinal(in, offset, length);
            } catch (AEADBadTagException e) {
                throw new DataIntegrityException("Sealed segment " + index + " failed authentication: wrong AAD, or "
                        + "the data was changed, reordered or truncated", e, null, null);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("AES-GCM failed", e);
            }
        }

        private Cipher cipher(int mode, long index, boolean last) throws GeneralSecurityException {
            if (index > 0xFFFFFFFFL) {
                throw new InvalidArgumentException("HLXS data is limited to 2^32 segments");
            }
            byte[] nonce = ByteBuffer.allocate(12).put(noncePrefix).putInt((int) index).put((byte) (last ? 1 : 0))
                    .array();
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(mode, key, new GCMParameterSpec(TAG_LENGTH * 8, nonce));
            gcm.updateAAD(aad);
            return gcm;
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] c = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, c, a.length, b.length);
        return c;
    }
}
