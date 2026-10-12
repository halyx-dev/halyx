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
package org.kamranzafar.halyx.azure.blob;

import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Exposes a download body as a blocking {@link InputStream}, with a small prefetch so the HTTP
 * connection is read only as fast as the caller consumes. Closing early cancels the download.
 */
final class ByteBufferStreamInputStream extends InputStream {

    private static final int PREFETCH = 8;

    private final Stream<ByteBuffer> stream;
    private final Iterator<ByteBuffer> buffers;
    private ByteBuffer current = ByteBuffer.allocate(0);
    private boolean closed;

    ByteBufferStreamInputStream(Flux<ByteBuffer> body) {
        this.stream = body.toStream(PREFETCH);
        this.buffers = stream.iterator();
    }

    @Override
    public int read() throws IOException {
        return advance() ? current.get() & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        if (!advance()) {
            return -1;
        }
        int n = Math.min(len, current.remaining());
        current.get(b, off, n);
        return n;
    }

    @Override
    public int available() {
        return closed ? 0 : current.remaining();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            stream.close();
        }
    }

    private boolean advance() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        try {
            while (!current.hasRemaining()) {
                if (!buffers.hasNext()) {
                    return false;
                }
                current = buffers.next();
            }
            return true;
        } catch (RuntimeException e) {
            Throwable cause = Exceptions.unwrap(e);
            throw cause instanceof IOException io ? io : new IOException("Blob download failed", cause);
        }
    }
}
