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

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Reads a plaintext range from sealed segments that start at segment {@code first}. Which segment is the last is known
 * from the sealed length, so each segment is opened with the right final flag: a truncated or extended blob fails
 * authentication rather than reading short. Each segment is authenticated before any of its bytes are returned.
 * Closes the source.
 */
final class RangeOpeningInputStream extends InputStream {

    private final InputStream source;
    private final Hlxs.Segments segments;
    private final long lastNeeded;
    private final long finalIndex;
    private final byte[] chunk;
    private long index;
    private long skip;
    private long remaining;
    private byte[] plaintext = new byte[0];
    private int position;

    /**
     * @param first      the segment {@code source} starts at
     * @param lastNeeded the last segment the range touches
     * @param finalIndex the data's final segment
     * @param skip       plaintext bytes of segment {@code first} before the range
     * @param length     plaintext bytes in the range
     */
    RangeOpeningInputStream(InputStream source, Hlxs.Segments segments, long first, long lastNeeded, long finalIndex,
                            long skip, long length) {
        this.source = Objects.requireNonNull(source, "source");
        this.segments = segments;
        this.index = first;
        this.lastNeeded = lastNeeded;
        this.finalIndex = finalIndex;
        this.skip = skip;
        this.remaining = length;
        this.chunk = new byte[segments.segmentSize() + Hlxs.TAG_LENGTH];
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        if (remaining == 0) {
            return -1; // the range is read, even if the segment holds more
        }
        while (position == plaintext.length) {
            if (index > lastNeeded) {
                return -1;
            }
            nextSegment();
        }
        int n = (int) Math.min(Math.min(len, plaintext.length - position), remaining);
        System.arraycopy(plaintext, position, b, off, n);
        position += n;
        remaining -= n;
        return n;
    }

    /** Opens the next segment, for a range read or to authenticate an empty plaintext's only segment. */
    void nextSegment() throws IOException {
        boolean last = index == finalIndex;
        int length = readInto();
        if (!last && length < chunk.length) {
            throw new DataIntegrityException("The sealed data is truncated at segment " + index);
        }
        if (length < Hlxs.TAG_LENGTH) {
            throw new DataIntegrityException("The sealed data is truncated at segment " + index);
        }
        Arrays.fill(plaintext, (byte) 0);
        plaintext = segments.open(index++, last, chunk, 0, length);
        position = (int) Math.min(skip, plaintext.length);
        skip = 0;
    }

    private int readInto() throws IOException {
        int total = 0;
        while (total < chunk.length) {
            int n = source.read(chunk, total, chunk.length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        Arrays.fill(plaintext, (byte) 0);
        source.close();
    }
}
