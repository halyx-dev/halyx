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
package dev.halyx.storage.spi;

import dev.halyx.storage.Checksums;
import dev.halyx.storage.PutResult;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Uploads the parts of a {@link MultipartUploadSession} with up to {@code concurrency} parts in flight.
 * {@link #submit} blocks while all slots are busy (backpressure), so a writer never buffers more than
 * {@code concurrency + 1} parts. Parts run on virtual threads; buffers return to the {@link BufferPool}
 * once uploaded. The first failure is reported by the next call and fails the upload.
 */
public final class PartUploader {

    private final MultipartUploadSession session;
    private final int concurrency;
    private final BufferPool pool;
    private final Function<RuntimeException, RuntimeException> translate;
    private final Semaphore slots;
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    /**
     * @param translate maps a driver exception to the portable error model
     */
    public PartUploader(MultipartUploadSession session, int concurrency, BufferPool pool,
                        Function<RuntimeException, RuntimeException> translate) {
        this.session = Objects.requireNonNull(session, "session");
        this.concurrency = Math.max(1, Math.min(concurrency, session.maxConcurrentParts()));
        this.pool = Objects.requireNonNull(pool, "pool");
        this.translate = Objects.requireNonNull(translate, "translate");
        this.slots = new Semaphore(this.concurrency);
    }

    /** Effective concurrency: the requested value capped by what the session supports. */
    public int concurrency() {
        return concurrency;
    }

    /** Uploads {@code buffer[0, length)} as part {@code partNumber}; takes ownership of the buffer. */
    public void submit(int partNumber, byte[] buffer, int length) {
        rethrow();
        if (concurrency == 1) {
            try {
                session.uploadPart(partNumber, buffer, length);
            } catch (RuntimeException e) {
                throw translate.apply(e);
            } finally {
                pool.release(buffer);
            }
            return;
        }
        slots.acquireUninterruptibly();
        rethrow(() -> slots.release());
        Thread.ofVirtual().name("halyx-part-" + partNumber).start(() -> {
            try {
                session.uploadPart(partNumber, buffer, length);
            } catch (RuntimeException e) {
                failure.compareAndSet(null, translate.apply(e));
            } finally {
                pool.release(buffer);
                slots.release();
            }
        });
    }

    /** Waits for in-flight parts, then commits the upload. */
    public PutResult complete(Checksums expected) {
        awaitInFlight();
        rethrow();
        try {
            return session.complete(expected);
        } catch (RuntimeException e) {
            throw translate.apply(e);
        }
    }

    /** Waits for in-flight parts, then discards the upload. Never throws. */
    public void abort() {
        awaitInFlight();
        try {
            session.abort();
        } catch (RuntimeException ignored) {
            // abort is best effort
        }
    }

    private void awaitInFlight() {
        if (concurrency > 1) {
            slots.acquireUninterruptibly(concurrency);
            slots.release(concurrency);
        }
    }

    private void rethrow() {
        rethrow(() -> { });
    }

    private void rethrow(Runnable beforeThrow) {
        RuntimeException e = failure.get();
        if (e != null) {
            beforeThrow.run();
            throw e;
        }
    }
}
