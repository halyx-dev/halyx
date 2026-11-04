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
 * Reads the plaintext of HLXS sealed data whose header was already read, one segment at a time. Each segment is
 * authenticated before any of its bytes are returned; a missing final segment fails at the end of the stream with
 * {@link DataIntegrityException}, so a reader sees an error, never a silently shortened plaintext. Closes the source.
 */
final class OpeningInputStream extends InputStream {

    private final InputStream source;
    private final Hlxs.Segments segments;
    private final byte[] chunk;
    private byte[] plaintext = new byte[0];
    private int position;
    private long index;
    private int lookahead = -1;
    private boolean finished;

    OpeningInputStream(InputStream source, Hlxs.Segments segments) {
        this.source = Objects.requireNonNull(source, "source");
        this.segments = segments;
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
        while (position == plaintext.length) {
            if (finished) {
                return -1;
            }
            nextSegment();
        }
        int n = Math.min(len, plaintext.length - position);
        System.arraycopy(plaintext, position, b, off, n);
        position += n;
        return n;
    }

    private void nextSegment() throws IOException {
        int length = 0;
        if (lookahead >= 0) {
            chunk[0] = (byte) lookahead;
            length = 1;
            lookahead = -1;
        }
        length += readInto(length);
        boolean last;
        if (length < chunk.length) {
            last = true;
        } else {
            int next = source.read();
            last = next < 0;
            lookahead = next;
        }
        if (length < Hlxs.TAG_LENGTH) {
            throw new DataIntegrityException("The sealed data is truncated at segment " + index);
        }
        Arrays.fill(plaintext, (byte) 0);
        plaintext = segments.open(index++, last, chunk, 0, length);
        position = 0;
        finished = last;
    }

    private int readInto(int offset) throws IOException {
        int total = 0;
        while (offset + total < chunk.length) {
            int n = source.read(chunk, offset + total, chunk.length - offset - total);
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
