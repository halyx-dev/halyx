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

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Writes HLXS sealed data to a sink: the header, then each segment as it fills. Only {@link #close()} writes the
 * final segment, so data that isn't closed is detectably truncated. Closes the sink.
 */
final class SealingOutputStream extends OutputStream {

    private final OutputStream sink;
    private final byte[] header;
    private final Hlxs.Segments segments;
    private final byte[] buffer;
    private int buffered;
    private long index;
    private boolean headerWritten;
    private boolean closed;

    SealingOutputStream(OutputStream sink, byte[] header, Hlxs.Segments segments) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.header = header;
        this.segments = segments;
        this.buffer = new byte[segments.segmentSize()];
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (closed) {
            throw new IOException("The sealing stream is closed");
        }
        writeHeader();
        while (len > 0) {
            if (buffered == buffer.length) {
                // A full segment is only written once more data shows it isn't the last one.
                sink.write(segments.seal(index++, false, buffer, 0, buffered));
                buffered = 0;
            }
            int n = Math.min(len, buffer.length - buffered);
            System.arraycopy(b, off, buffer, buffered, n);
            buffered += n;
            off += n;
            len -= n;
        }
    }

    private void writeHeader() throws IOException {
        if (!headerWritten) {
            sink.write(header);
            headerWritten = true;
        }
    }

    @Override
    public void flush() throws IOException {
        sink.flush();
    }

    /** Writes the final segment and closes the sink. */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            writeHeader();
            sink.write(segments.seal(index, true, buffer, 0, buffered));
        } finally {
            closed = true;
            Arrays.fill(buffer, (byte) 0);
            sink.close();
        }
    }
}
