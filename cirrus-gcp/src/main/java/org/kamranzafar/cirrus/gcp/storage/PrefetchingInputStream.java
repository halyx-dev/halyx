package org.kamranzafar.cirrus.gcp.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Starts reading the first {@code size} bytes of a stream on a virtual thread right away, so the
 * download's first round trip overlaps other work (such as the metadata lookup).
 */
final class PrefetchingInputStream extends InputStream {

    private final InputStream in;
    private final CompletableFuture<byte[]> first;
    private byte[] head;
    private int position;

    PrefetchingInputStream(InputStream in, int size) {
        this.in = in;
        this.first = new CompletableFuture<>();
        Thread.ofVirtual().name("cirrus-gcs-prefetch").start(() -> {
            try {
                first.complete(in.readNBytes(size));
            } catch (Throwable t) {
                first.completeExceptionally(t);
            }
        });
    }

    @Override
    public int read() throws IOException {
        if (awaitHead()) {
            return head[position++] & 0xFF;
        }
        return in.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        if (awaitHead()) {
            int n = Math.min(len, head.length - position);
            System.arraycopy(head, position, b, off, n);
            position += n;
            return n;
        }
        return in.read(b, off, len);
    }

    @Override
    public void close() throws IOException {
        first.cancel(false);
        in.close();
    }

    /** True while prefetched bytes remain. */
    private boolean awaitHead() throws IOException {
        if (head == null) {
            try {
                head = first.get();
            } catch (ExecutionException e) {
                head = new byte[0];
                throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reading blob", e);
            }
        }
        return position < head.length;
    }
}
