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
package org.kamranzafar.cirrus.messaging;

import java.time.Duration;
import java.util.Objects;

/**
 * How a {@link MessageConsumer} pulls and handles messages.
 *
 * @param concurrency      most messages handled at once, each on its own virtual thread (default 16).
 *                         The consumer stops pulling while all are busy, so slow handlers create backpressure
 * @param batchSize        most messages per receive request (default 10, capped by the provider)
 * @param pollWait         long-poll wait when the subscription is empty (default 20s)
 * @param initialBackoff   delay before redelivering a message whose handler failed the first time (default 1s)
 * @param maxBackoff       the backoff doubles with each failed delivery up to this (default 5m)
 * @param extendLease      keep a message invisible for as long as its handler runs (default true)
 * @param shutdownTimeout  how long {@code close()} waits for running handlers (default 30s)
 */
public record ConsumerOptions(int concurrency, int batchSize, Duration pollWait, Duration initialBackoff,
                              Duration maxBackoff, boolean extendLease, Duration shutdownTimeout) {

    public static final ConsumerOptions DEFAULTS = new ConsumerOptions(16, 10, Duration.ofSeconds(20),
            Duration.ofSeconds(1), Duration.ofMinutes(5), true, Duration.ofSeconds(30));

    public ConsumerOptions {
        if (concurrency < 1) {
            throw new IllegalArgumentException("concurrency must be at least 1");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        Objects.requireNonNull(pollWait, "pollWait");
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
    }

    public ConsumerOptions withConcurrency(int concurrency) {
        return new ConsumerOptions(concurrency, batchSize, pollWait, initialBackoff, maxBackoff, extendLease, shutdownTimeout);
    }

    public ConsumerOptions withPollWait(Duration pollWait) {
        return new ConsumerOptions(concurrency, batchSize, pollWait, initialBackoff, maxBackoff, extendLease, shutdownTimeout);
    }

    public ConsumerOptions withBackoff(Duration initial, Duration max) {
        return new ConsumerOptions(concurrency, batchSize, pollWait, initial, max, extendLease, shutdownTimeout);
    }

    /** The redelivery delay after delivery attempt {@code attempt} (1-based) failed. */
    Duration backoff(int attempt) {
        long millis = initialBackoff.toMillis() << Math.min(Math.max(attempt, 1) - 1, 30);
        return Duration.ofMillis(Math.min(Math.max(millis, 0), maxBackoff.toMillis()));
    }
}
