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
package org.kamranzafar.halyx.storage.spi;

import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.storage.BlobKey;
import org.kamranzafar.halyx.storage.Checksums;
import org.kamranzafar.halyx.storage.ETag;
import org.kamranzafar.halyx.storage.PutResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PartUploaderTest {

    private final BufferPool pool = new BufferPool(Long.MAX_VALUE);

    @Test
    void uploadsPartsConcurrentlyUpToTheLimit() {
        var session = new SlowSession(Integer.MAX_VALUE);
        var uploader = new PartUploader(session, 3, pool, Function.identity());

        for (int part = 1; part <= 12; part++) {
            byte[] buffer = pool.acquire(16);
            buffer[0] = (byte) part;
            uploader.submit(part, buffer, 16);
        }
        uploader.complete(Checksums.none());

        assertThat(session.maxInFlight.get()).isEqualTo(3);
        assertThat(session.firstBytes).hasSize(12);
        for (int part = 1; part <= 12; part++) {
            assertThat(session.firstBytes.get(part)).isEqualTo((byte) part);
        }
        assertThat(session.completed).isTrue();
    }

    @Test
    void sessionLimitCapsConcurrencyAndRunsInline() {
        var session = new SlowSession(1);
        var uploader = new PartUploader(session, 8, pool, Function.identity());

        assertThat(uploader.concurrency()).isEqualTo(1);
        uploader.submit(1, pool.acquire(16), 16);
        uploader.submit(2, pool.acquire(16), 16);

        assertThat(session.threads).containsOnly(Thread.currentThread().getName());
        assertThat(session.maxInFlight.get()).isEqualTo(1);
    }

    @Test
    void firstFailureFailsLaterCallsAndIsTranslated() {
        var session = new SlowSession(Integer.MAX_VALUE) {
            @Override
            public void uploadPart(int partNumber, byte[] data, int length) {
                if (partNumber == 2) {
                    throw new IllegalStateException("part 2 failed");
                }
                super.uploadPart(partNumber, data, length);
            }
        };
        var uploader = new PartUploader(session, 4, pool,
                e -> new IllegalArgumentException("translated: " + e.getMessage(), e));

        uploader.submit(1, pool.acquire(16), 16);
        uploader.submit(2, pool.acquire(16), 16);

        assertThatThrownBy(() -> uploader.complete(Checksums.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("translated: part 2 failed");
        assertThatThrownBy(() -> uploader.submit(3, pool.acquire(16), 16))
                .hasMessage("translated: part 2 failed");
        assertThat(session.completed).isFalse();
        uploader.abort();
        assertThat(session.aborted).isTrue();
    }

    @Test
    void buffersReturnToThePool() {
        var uploader = new PartUploader(new SlowSession(Integer.MAX_VALUE), 2, pool, Function.identity());
        byte[] buffer = pool.acquire(32);

        uploader.submit(1, buffer, 32);
        uploader.complete(Checksums.none());

        assertThat(pool.acquire(32)).isSameAs(buffer);
    }

    @Test
    void poolKeepsAtMostItsLimitIdle() {
        var small = new BufferPool(64);
        small.release(new byte[48]);
        small.release(new byte[48]);

        assertThat(small.idleBytes()).isEqualTo(48);
    }

    private static class SlowSession implements MultipartUploadSession {
        final int limit;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final Map<Integer, Byte> firstBytes = new ConcurrentHashMap<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        volatile boolean completed;
        volatile boolean aborted;

        SlowSession(int limit) {
            this.limit = limit;
        }

        @Override
        public int maxConcurrentParts() {
            return limit;
        }

        @Override
        public void uploadPart(int partNumber, byte[] data, int length) {
            int now = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(now, Math::max);
            threads.add(Thread.currentThread().getName());
            firstBytes.put(partNumber, data[0]);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        }

        @Override
        public PutResult complete(Checksums expected) {
            assertThat(inFlight.get()).isZero();
            completed = true;
            return new PutResult(BlobKey.of("k"), ETag.of("e"));
        }

        @Override
        public void abort() {
            aborted = true;
        }
    }
}
