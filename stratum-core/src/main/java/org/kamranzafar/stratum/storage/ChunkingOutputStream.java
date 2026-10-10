package org.kamranzafar.stratum.storage;

import org.kamranzafar.stratum.storage.spi.BlobStoreDriver;
import org.kamranzafar.stratum.storage.spi.MultipartUploadSession;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Buffers written bytes into parts and uploads them one at a time, so memory use is one part
 * whatever the blob size. Content that fits in a single part is uploaded with one plain put instead,
 * so small writes cost one request.
 *
 * <p>Driver errors are translated to the portable error model as they happen.
 */
final class ChunkingOutputStream extends OutputStream {

    private final BlobStoreDriver driver;
    private final BlobKey key;
    private final PutOptions options;
    private final byte[] buffer;
    private int count;
    private int partNumber;
    private MultipartUploadSession session;
    private boolean closed;

    ChunkingOutputStream(BlobStoreDriver driver, BlobKey key, PutOptions options) {
        this.driver = driver;
        this.key = key;
        this.options = options;
        this.buffer = new byte[options.partSize()];
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        buffer[count++] = (byte) b;
        if (count == buffer.length) {
            flushPart();
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        Objects.checkFromIndexSize(off, len, b.length);
        while (len > 0) {
            int n = Math.min(len, buffer.length - count);
            System.arraycopy(b, off, buffer, count, n);
            count += n;
            off += n;
            len -= n;
            if (count == buffer.length) {
                flushPart();
            }
        }
    }

    /** Stops accepting writes; the upload is committed or aborted by {@link #finish()} / {@link #abort()}. */
    @Override
    public void close() {
        closed = true;
    }

    PutResult finish() {
        closed = true;
        try {
            if (session == null) {
                return driver.put(key, new BlobContent.OfBytes(Arrays.copyOf(buffer, count)), options);
            }
            if (count > 0) {
                session.uploadPart(++partNumber, buffer, count);
                count = 0;
            }
            return session.complete();
        } catch (RuntimeException e) {
            throw driver.translate(e);
        }
    }

    void abort() {
        closed = true;
        if (session != null) {
            session.abort();
        }
    }

    private void flushPart() {
        try {
            if (session == null) {
                session = driver.startMultipartUpload(key, options);
            }
            session.uploadPart(++partNumber, buffer, count);
            count = 0;
        } catch (RuntimeException e) {
            throw driver.translate(e);
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Blob stream is closed");
        }
    }
}
