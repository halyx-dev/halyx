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
package org.kamranzafar.halyx.storage.spi;

import org.kamranzafar.halyx.storage.ChecksumAlgorithm;
import org.kamranzafar.halyx.storage.Checksums;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

/** Computes checksums incrementally, in the encoding providers use. Not thread-safe. */
public final class ChecksumCalculator {

    private final Checksum crc32c;
    private final Checksum crc32;
    private final MessageDigest md5;

    public ChecksumCalculator(Set<ChecksumAlgorithm> algorithms) {
        this.crc32c = algorithms.contains(ChecksumAlgorithm.CRC32C) ? new CRC32C() : null;
        this.crc32 = algorithms.contains(ChecksumAlgorithm.CRC32) ? new CRC32() : null;
        this.md5 = algorithms.contains(ChecksumAlgorithm.MD5) ? newMd5() : null;
    }

    /** CRC32C and MD5 of a byte range, as stored by providers that record both. */
    public static Checksums of(byte[] data, int offset, int length) {
        return of(Set.of(ChecksumAlgorithm.CRC32C, ChecksumAlgorithm.MD5), data, offset, length);
    }

    public static Checksums of(Set<ChecksumAlgorithm> algorithms, byte[] data, int offset, int length) {
        ChecksumCalculator calculator = new ChecksumCalculator(algorithms);
        calculator.update(data, offset, length);
        return calculator.result();
    }

    /** Raw MD5 digest of a byte range, as used for per-request integrity headers. */
    public static byte[] md5(byte[] data, int offset, int length) {
        MessageDigest digest = newMd5();
        digest.update(data, offset, length);
        return digest.digest();
    }

    /** Base64 big-endian CRC32C of a byte range. */
    public static String crc32c(byte[] data, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(data, offset, length);
        return encode(crc.getValue());
    }

    public void update(byte[] data, int offset, int length) {
        if (crc32c != null) {
            crc32c.update(data, offset, length);
        }
        if (crc32 != null) {
            crc32.update(data, offset, length);
        }
        if (md5 != null) {
            md5.update(data, offset, length);
        }
    }

    public Checksums result() {
        Map<ChecksumAlgorithm, String> values = new EnumMap<>(ChecksumAlgorithm.class);
        if (crc32c != null) {
            values.put(ChecksumAlgorithm.CRC32C, encode(crc32c.getValue()));
        }
        if (crc32 != null) {
            values.put(ChecksumAlgorithm.CRC32, encode(crc32.getValue()));
        }
        if (md5 != null) {
            values.put(ChecksumAlgorithm.MD5, Base64.getEncoder().encodeToString(md5.digest()));
        }
        return new Checksums(values);
    }

    private static String encode(long crc) {
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(4).putInt((int) crc).array());
    }

    private static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by every Java platform", e);
        }
    }
}
