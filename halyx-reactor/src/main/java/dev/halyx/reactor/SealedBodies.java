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
package dev.halyx.reactor;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Sealing and opening HLXS bodies as {@code Flux}es, over the key API's blocking streams. Each runs through
 * {@link Calls#stream}: opened on the scheduler when subscribed (which makes the wrap or unwrap call), then pulled
 * chunk by chunk as the subscriber asks, reading the input publisher a few chunks at a time on the same threads.
 */
final class SealedBodies {

    /** Input chunks requested from the publisher ahead of use. */
    private static final int PREFETCH = 4;

    private SealedBodies() {
    }

    /**
     * Seals {@code plaintext} through the stream {@code open} makes around a sink. Only a completed {@code plaintext}
     * closes the sealing stream, which writes the final segment: if it fails, or the subscriber cancels, the sealed
     * output stays detectably truncated.
     */
    static Flux<ByteBuffer> seal(Calls calls, Publisher<ByteBuffer> plaintext,
                                 Function<OutputStream, OutputStream> open) {
        return calls.stream(() -> {
            Stream<ByteBuffer> input = Flux.from(plaintext).toStream(PREFETCH);
            try {
                Drain sink = new Drain();
                OutputStream sealing = open.apply(sink);
                Iterator<ByteBuffer> chunks = input.iterator();
                return stream(new Iterator<>() {
                    private ByteBuffer next;
                    private boolean done;

                    @Override
                    public boolean hasNext() {
                        try {
                            // A segment is written only once it is full, so several input chunks may give no output.
                            while (next == null && !done) {
                                if (chunks.hasNext()) {
                                    write(sealing, chunks.next());
                                } else {
                                    sealing.close();
                                    done = true;
                                }
                                next = sink.drain();
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException("Could not seal the body", e);
                        }
                        return next != null;
                    }

                    @Override
                    public ByteBuffer next() {
                        if (!hasNext()) {
                            throw new NoSuchElementException();
                        }
                        ByteBuffer chunk = next;
                        next = null;
                        return chunk;
                    }
                }).onClose(input::close);
            } catch (RuntimeException | Error e) {
                input.close();
                throw e;
            }
        });
    }

    /** Opens {@code sealed} through the stream {@code open} makes around it, in chunks of up to 64 KiB. */
    static Flux<ByteBuffer> open(Calls calls, Publisher<ByteBuffer> sealed, Function<InputStream, InputStream> open) {
        return calls.stream(() -> {
            Stream<ByteBuffer> input = Flux.from(sealed).toStream(PREFETCH);
            InputStream opened;
            try {
                opened = open.apply(new ChunksInputStream(input));
            } catch (RuntimeException | Error e) {
                input.close();
                throw e;
            }
            return stream(new Iterator<>() {
                private ByteBuffer next;
                private boolean done;

                @Override
                public boolean hasNext() {
                    if (next == null && !done) {
                        try {
                            byte[] chunk = opened.readNBytes(ReactiveBlobStore.DEFAULT_CHUNK_SIZE);
                            if (chunk.length == 0) {
                                done = true;
                            } else {
                                next = ByteBuffer.wrap(chunk);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException("Could not open the body", e);
                        }
                    }
                    return next != null;
                }

                @Override
                public ByteBuffer next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    ByteBuffer chunk = next;
                    next = null;
                    return chunk;
                }
            }).onClose(() -> {
                try {
                    opened.close(); // zeroes its plaintext buffer and closes the input
                } catch (IOException e) {
                    // the body is already read or abandoned
                }
            });
        });
    }

    private static Stream<ByteBuffer> stream(Iterator<ByteBuffer> iterator) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false);
    }

    private static void write(OutputStream out, ByteBuffer chunk) throws IOException {
        ByteBuffer source = chunk.duplicate(); // the caller's buffer keeps its position
        if (source.hasArray()) {
            out.write(source.array(), source.arrayOffset() + source.position(), source.remaining());
        } else {
            byte[] copy = new byte[source.remaining()];
            source.get(copy);
            out.write(copy);
        }
    }

    /** Collects what the sealing stream writes, handed on chunk by chunk. */
    private static final class Drain extends ByteArrayOutputStream {
        /** What was written since the last drain, or null if nothing was. */
        ByteBuffer drain() {
            if (count == 0) {
                return null;
            }
            ByteBuffer chunk = ByteBuffer.wrap(toByteArray());
            reset();
            return chunk;
        }
    }

    /** The chunks of a blocking stream of buffers as an {@code InputStream}; closing it closes the stream. */
    private static final class ChunksInputStream extends InputStream {
        private final Stream<ByteBuffer> chunks;
        private final Iterator<ByteBuffer> iterator;
        private ByteBuffer current;

        ChunksInputStream(Stream<ByteBuffer> chunks) {
            this.chunks = chunks;
            this.iterator = chunks.iterator();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            while (current == null || !current.hasRemaining()) {
                if (!iterator.hasNext()) {
                    return -1;
                }
                current = iterator.next().duplicate();
            }
            int n = Math.min(len, current.remaining());
            current.get(b, off, n);
            return n;
        }

        @Override
        public void close() {
            chunks.close();
        }
    }
}
