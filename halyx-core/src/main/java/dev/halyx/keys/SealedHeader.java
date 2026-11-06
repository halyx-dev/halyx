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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * The header of HLXS sealed data, read on its own, for random access to sealed data stored somewhere else, such as a
 * blob: read the header, work out which sealed bytes hold a plaintext range, fetch only those, and open them with
 * {@link KeyStore#decryptingStream(SealedHeader, InputStream, long, long, long, byte[])}.
 *
 * <p>The body is the plaintext in segments of {@link #segmentSize()} bytes, each followed by a 16-byte tag, the last
 * one possibly shorter; segment {@code i} holds plaintext bytes {@code [i × segmentSize, (i + 1) × segmentSize)} and
 * starts {@code length() + i × (segmentSize + 16)} bytes into the sealed data.
 */
public final class SealedHeader {

    private final Hlxs.Header header;

    private SealedHeader(Hlxs.Header header) {
        this.header = header;
    }

    /**
     * Reads exactly the header from the start of {@code sealed}, leaving the stream at the first segment. Throws
     * {@code InvalidArgumentException} if it isn't HLXS data, and {@code DataIntegrityException} if it ends inside the
     * header.
     */
    public static SealedHeader read(InputStream sealed) {
        Objects.requireNonNull(sealed, "sealed");
        try {
            return new SealedHeader(Hlxs.parseHeader(sealed instanceof DataInputStream d ? d
                    : new DataInputStream(sealed)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the sealed data's header", e);
        }
    }

    /** The header's length in bytes: where the first segment starts. */
    public int length() {
        return header.length();
    }

    /** Plaintext bytes per segment. */
    public int segmentSize() {
        return header.segmentSize();
    }

    /** The data key's wrapped copies, one per key the data was sealed for. */
    public List<WrappedKey> keys() {
        return header.keys();
    }

    /**
     * The plaintext length of sealed data {@code sealedLength} bytes long with this header. Throws
     * {@code DataIntegrityException} if no plaintext seals to that length: the data was truncated or extended.
     */
    public long plaintextLength(long sealedLength) {
        long body = sealedLength - header.length();
        int full = header.segmentSize() + Hlxs.TAG_LENGTH;
        if (body < Hlxs.TAG_LENGTH) {
            throw new DataIntegrityException("The sealed data is truncated: no final segment");
        }
        long count = (body + full - 1) / full;
        long last = body - (count - 1) * full;
        if (last < Hlxs.TAG_LENGTH) {
            throw new DataIntegrityException("The sealed data's last segment is truncated");
        }
        return body - count * Hlxs.TAG_LENGTH;
    }

    /** The number of segments in sealed data {@code sealedLength} bytes long with this header. */
    long segments(long sealedLength) {
        long body = sealedLength - header.length();
        int full = header.segmentSize() + Hlxs.TAG_LENGTH;
        return Math.max(1, (body + full - 1) / full);
    }

    /** Where the segment holding plaintext byte {@code plaintextOffset} starts in the sealed data. */
    public long segmentStart(long plaintextOffset) {
        if (plaintextOffset < 0) {
            throw new InvalidArgumentException("A plaintext offset is never negative (got " + plaintextOffset + ")");
        }
        return header.length() + (plaintextOffset / header.segmentSize()) * (header.segmentSize() + (long) Hlxs.TAG_LENGTH);
    }

    /**
     * Where the segment holding plaintext byte {@code plaintextEnd - 1} ends in the sealed data, which is
     * {@code sealedLength} bytes long: fetching {@code [segmentStart(offset), segmentEnd(offset + length, sealedLength))}
     * gets every segment a plaintext range touches.
     */
    public long segmentEnd(long plaintextEnd, long sealedLength) {
        if (plaintextEnd <= 0) {
            return Math.min(sealedLength, header.length() + header.segmentSize() + (long) Hlxs.TAG_LENGTH);
        }
        long last = (plaintextEnd - 1) / header.segmentSize();
        return Math.min(sealedLength, header.length() + (last + 1) * (header.segmentSize() + (long) Hlxs.TAG_LENGTH));
    }

    Hlxs.Header header() {
        return header;
    }

    @Override
    public String toString() {
        return "SealedHeader[" + header.keys().size() + " key(s), segments of " + header.segmentSize() + " bytes]";
    }
}
