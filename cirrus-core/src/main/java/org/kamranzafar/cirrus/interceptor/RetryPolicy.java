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
package org.kamranzafar.cirrus.interceptor;

import java.time.Duration;
import java.util.Objects;

/**
 * Exponential backoff with full jitter.
 *
 * @param maxAttempts    total attempts including the first, at least 1
 * @param initialBackoff upper bound of the delay before the first retry
 * @param maxBackoff     cap on the delay between attempts
 * @param multiplier     growth factor of the delay bound per attempt
 */
public record RetryPolicy(int maxAttempts, Duration initialBackoff, Duration maxBackoff, double multiplier) {

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be >= 1.0");
        }
    }

    public static RetryPolicy defaults() {
        return new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2), 2.0);
    }

    /** Upper bound of the delay before retry number {@code retry} (1-based). */
    Duration backoffBound(int retry) {
        double millis = initialBackoff.toMillis() * Math.pow(multiplier, retry - 1);
        return Duration.ofMillis((long) Math.min(millis, maxBackoff.toMillis()));
    }
}
