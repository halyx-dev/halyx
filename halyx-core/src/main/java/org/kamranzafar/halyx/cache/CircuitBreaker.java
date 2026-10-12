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
package org.kamranzafar.halyx.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Minimal consecutive-failure breaker for fail-open caches: after {@code threshold} failures in a
 * row, calls are skipped for {@code openFor}; then one call is let through, and its outcome closes or
 * re-opens the breaker.
 */
final class CircuitBreaker {

    private final int threshold;
    private final long openForNanos;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntil = new AtomicLong(Long.MIN_VALUE);

    CircuitBreaker(int threshold, Duration openFor) {
        this.threshold = threshold;
        this.openForNanos = openFor.toNanos();
    }

    boolean allowsCall() {
        long until = openUntil.get();
        if (until == Long.MIN_VALUE) {
            return true;
        }
        long now = System.nanoTime();
        // Half-open: exactly one caller wins the right to probe once the open period is over.
        return now - until >= 0 && openUntil.compareAndSet(until, now + openForNanos);
    }

    void onSuccess() {
        consecutiveFailures.set(0);
        openUntil.set(Long.MIN_VALUE);
    }

    void onFailure() {
        if (consecutiveFailures.incrementAndGet() >= threshold) {
            openUntil.set(System.nanoTime() + openForNanos);
        }
    }

    boolean isOpen() {
        return openUntil.get() != Long.MIN_VALUE;
    }
}
