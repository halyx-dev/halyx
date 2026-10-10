package org.kamranzafar.cirrus.storage;

import org.kamranzafar.cirrus.storage.spi.BufferPool;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Reads {@code [start, end)} of a blob as concurrent ranged requests, keeping up to {@code parallelism}
 * parts in flight or buffered ahead of the reader, and returns the bytes in order. Parts are fetched on
 * virtual threads into pooled buffers. A failed part fails the stream at the point the reader reaches it.
 */
final class ParallelRangeInputStream extends InputStream {

    /** Fills {@code buffer[0, length)} with the blob's bytes at {@code offset}. */
    @FunctionalInterface
    interface PartFetcher {
        void fetch(long offset, byte[] buffer, int length);
    }

    private record Part(byte[] buffer, int length) {
    }

    private final long end;
    private final int partSize;
    private final int parallelism;
    private final BufferPool pool;
    private final PartFetcher fetcher;
    private final ArrayDeque<CompletableFuture<Part>> window = new ArrayDeque<>();
    private long nextOffset;
    private Part current;
    private int position;
    private boolean closed;

    ParallelRangeInputStream(long start, long end, int partSize, int parallelism, BufferPool pool,
                             PartFetcher fetcher) {
        this.nextOffset = start;
        this.end = end;
        this.partSize = partSize;
        this.parallelism = parallelism;
        this.pool = pool;
        this.fetcher = fetcher;
        schedule();
    }

    @Override
    public int read() throws IOException {
        return advance() ? current.buffer[position++] & 0xFF : -1;
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
        int n = Math.min(len, current.length - position);
        System.arraycopy(current.buffer, position, b, off, n);
        position += n;
        return n;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        releaseCurrent();
        // Parts still in flight finish on their own; cancelling here makes them return their buffers.
        window.forEach(part -> {
            if (!part.cancel(false)) {
                part.thenAccept(p -> pool.release(p.buffer));
            }
        });
        window.clear();
    }

    private boolean advance() throws IOException {
        if (closed) {
            throw new IOException("Stream closed");
        }
        if (current != null && position < current.length) {
            return true;
        }
        releaseCurrent();
        CompletableFuture<Part> next = window.poll();
        if (next == null) {
            return false;
        }
        try {
            current = next.join();
        } catch (CompletionException e) {
            close();
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException("Blob part download failed", e.getCause());
        } catch (CancellationException e) {
            throw new IOException("Stream closed", e);
        }
        // Refill only once this part has arrived, so at most `parallelism` parts are ever in flight.
        schedule();
        position = 0;
        return current.length > 0 || advance();
    }

    /** Tops the window up to {@code parallelism} parts. */
    private void schedule() {
        while (window.size() < parallelism && nextOffset < end) {
            long offset = nextOffset;
            int length = (int) Math.min(partSize, end - offset);
            nextOffset += length;
            var part = new CompletableFuture<Part>();
            Thread.ofVirtual().name("cirrus-range-" + offset).start(() -> {
                byte[] buffer = pool.acquire(partSize);
                try {
                    fetcher.fetch(offset, buffer, length);
                    if (!part.complete(new Part(buffer, length))) {
                        pool.release(buffer); // cancelled by close()
                    }
                } catch (Throwable t) {
                    pool.release(buffer);
                    part.completeExceptionally(t);
                }
            });
            window.add(part);
        }
    }

    private void releaseCurrent() {
        if (current != null) {
            pool.release(current.buffer);
            current = null;
        }
    }
}
