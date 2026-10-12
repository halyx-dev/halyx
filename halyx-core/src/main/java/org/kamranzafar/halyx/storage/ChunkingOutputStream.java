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
package org.kamranzafar.halyx.storage;

import org.kamranzafar.halyx.storage.spi.BlobStoreDriver;
import org.kamranzafar.halyx.storage.spi.BufferPool;
import org.kamranzafar.halyx.storage.spi.ChecksumCalculator;
import org.kamranzafar.halyx.storage.spi.PartUploader;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Buffers written bytes into parts and uploads them through a {@link PartUploader}, with up to
 * {@link PutOptions#uploadConcurrency()} parts in flight. Memory is bounded by
 * {@code (concurrency + 1) * partSize}, whatever the blob size, and buffers come from the shared
 * {@link BufferPool}. Content that fits in a single part is uploaded with one plain put instead.
 */
final class ChunkingOutputStream extends OutputStream {

    private final BlobStoreDriver driver;
    private final BlobKey key;
    private final PutOptions options;
    private final BufferPool pool = BufferPool.shared();
    private final ChecksumCalculator checksums;
    private byte[] buffer;
    private int count;
    private int partNumber;
    private PartUploader uploader;
    private boolean closed;
    private Checksums expected = Checksums.none();

    ChunkingOutputStream(BlobStoreDriver driver, BlobKey key, PutOptions options) {
        this.driver = driver;
        this.key = key;
        this.options = options;
        this.buffer = pool.acquire(options.partSize());
        this.checksums = options.validateChecksum() ? new ChecksumCalculator(driver.uploadChecksums()) : null;
    }

    /** Checksums of everything written, once {@link #finish()} has run; none unless validation was requested. */
    Checksums expectedChecksums() {
        return expected;
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
            if (uploader == null) {
                byte[] content = Arrays.copyOf(buffer, count);
                releaseBuffer();
                if (checksums != null) {
                    checksums.update(content, 0, content.length);
                    expected = checksums.result();
                }
                return driver.put(key, new BlobContent.OfBytes(content), options, expected);
            }
            if (count > 0) {
                submitPart();
            } else {
                releaseBuffer();
            }
            if (checksums != null) {
                expected = checksums.result();
            }
            return uploader.complete(expected);
        } catch (RuntimeException e) {
            throw driver.translate(e);
        }
    }

    void abort() {
        closed = true;
        releaseBuffer();
        if (uploader != null) {
            uploader.abort();
        }
    }

    private void flushPart() {
        try {
            if (uploader == null) {
                uploader = new PartUploader(driver.startMultipartUpload(key, options), options.uploadConcurrency(),
                        pool, driver::translate);
            }
            submitPart();
            buffer = pool.acquire(options.partSize());
        } catch (RuntimeException e) {
            throw driver.translate(e);
        }
    }

    /** Hands the current buffer to the uploader (which releases it), checksumming it first in order. */
    private void submitPart() {
        if (checksums != null) {
            checksums.update(buffer, 0, count);
        }
        byte[] part = buffer;
        int length = count;
        buffer = null;
        count = 0;
        uploader.submit(++partNumber, part, length);
    }

    private void releaseBuffer() {
        if (buffer != null) {
            pool.release(buffer);
            buffer = null;
        }
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Blob stream is closed");
        }
    }
}
