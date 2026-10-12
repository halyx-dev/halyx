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

import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.error.ThrottledException;
import org.kamranzafar.halyx.storage.spi.BufferPool;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParallelRangeInputStreamTest {

    private static final int PART = 1000;

    private final byte[] blob = new byte[10 * PART + 123];
    private final BufferPool pool = BufferPool.shared();

    {
        new Random(7).nextBytes(blob);
    }

    @Test
    void returnsTheRangeInOrderWithBoundedLookahead() throws IOException {
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        var in = new ParallelRangeInputStream(5, blob.length, PART, 3, pool, (offset, buffer, length) -> {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            sleep(5 + (int) (offset % 7)); // finish out of order
            System.arraycopy(blob, (int) offset, buffer, 0, length);
            inFlight.decrementAndGet();
        });

        byte[] read = in.readAllBytes();

        assertThat(read).isEqualTo(java.util.Arrays.copyOfRange(blob, 5, blob.length));
        assertThat(maxInFlight.get()).isBetween(1, 3);
        assertThat(in.read()).isEqualTo(-1);
    }

    @Test
    void failedPartFailsTheStreamWhenReached() throws IOException {
        var in = new ParallelRangeInputStream(0, blob.length, PART, 4, pool, (offset, buffer, length) -> {
            if (offset == 2 * PART) {
                throw new ThrottledException("slow down");
            }
            System.arraycopy(blob, (int) offset, buffer, 0, length);
        });

        assertThat(in.readNBytes(2 * PART)).isEqualTo(java.util.Arrays.copyOf(blob, 2 * PART));
        assertThatThrownBy(in::read).isInstanceOf(ThrottledException.class).hasMessage("slow down");
        assertThatThrownBy(in::read).isInstanceOf(IOException.class).hasMessage("Stream closed");
    }

    @Test
    void closingEarlyStopsSchedulingParts() throws IOException {
        var fetched = new AtomicInteger();
        var in = new ParallelRangeInputStream(0, blob.length, PART, 2, pool, (offset, buffer, length) -> {
            fetched.incrementAndGet();
            System.arraycopy(blob, (int) offset, buffer, 0, length);
        });

        in.read();
        in.close();
        sleep(50);

        assertThat(fetched.get()).isLessThanOrEqualTo(3);
        assertThatThrownBy(in::read).isInstanceOf(IOException.class);
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
