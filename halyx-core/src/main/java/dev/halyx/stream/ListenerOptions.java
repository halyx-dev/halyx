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
package dev.halyx.stream;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * How a {@link StreamListener} reads and handles records. Each partition is handled in order on its own virtual
 * thread, and partitions run in parallel.
 *
 * @param pollWait            longest wait for records in one read (default 1s)
 * @param maxBufferedRecords  records read ahead per partition; reading that partition pauses beyond this
 *                            (default 500)
 * @param initialBackoff      delay before retrying a record whose handler failed the first time (default 1s)
 * @param maxBackoff          the backoff doubles with each failure up to this (default 5m)
 * @param maxAttempts         attempts per record before it goes to {@code deadLetter}; 0 retries for ever, holding
 *                            its partition (default 0)
 * @param deadLetter          where records go after {@code maxAttempts}; required when {@code maxAttempts > 0}
 * @param commitInterval      how often handled offsets are committed while running (default 1s). A lost commit
 *                            only means records are read again
 * @param shutdownTimeout     how long {@code close()} and a rebalance wait for running handlers (default 30s)
 */
public record ListenerOptions(Duration pollWait, int maxBufferedRecords, Duration initialBackoff, Duration maxBackoff,
                              int maxAttempts, StreamProducer deadLetter, Duration commitInterval,
                              Duration shutdownTimeout) {

    public static final ListenerOptions DEFAULTS = new ListenerOptions(Duration.ofSeconds(1), 500,
            Duration.ofSeconds(1), Duration.ofMinutes(5), 0, null, Duration.ofSeconds(1), Duration.ofSeconds(30));

    public ListenerOptions {
        Objects.requireNonNull(pollWait, "pollWait");
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        Objects.requireNonNull(commitInterval, "commitInterval");
        Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        if (maxBufferedRecords < 1) {
            throw new IllegalArgumentException("maxBufferedRecords must be at least 1");
        }
        if (maxAttempts < 0) {
            throw new IllegalArgumentException("maxAttempts must not be negative");
        }
        if (maxAttempts > 0 && deadLetter == null) {
            throw new IllegalArgumentException("maxAttempts needs a deadLetter producer to send exhausted records to");
        }
    }

    public ListenerOptions withPollWait(Duration pollWait) {
        return new ListenerOptions(pollWait, maxBufferedRecords, initialBackoff, maxBackoff, maxAttempts, deadLetter,
                commitInterval, shutdownTimeout);
    }

    public ListenerOptions withBackoff(Duration initial, Duration max) {
        return new ListenerOptions(pollWait, maxBufferedRecords, initial, max, maxAttempts, deadLetter,
                commitInterval, shutdownTimeout);
    }

    /** After {@code maxAttempts} failed attempts, send the record to {@code deadLetter} and move on. */
    public ListenerOptions withDeadLetter(StreamProducer deadLetter, int maxAttempts) {
        return new ListenerOptions(pollWait, maxBufferedRecords, initialBackoff, maxBackoff, maxAttempts,
                Objects.requireNonNull(deadLetter, "deadLetter"), commitInterval, shutdownTimeout);
    }

    public ListenerOptions withCommitInterval(Duration commitInterval) {
        return new ListenerOptions(pollWait, maxBufferedRecords, initialBackoff, maxBackoff, maxAttempts, deadLetter,
                commitInterval, shutdownTimeout);
    }

    public ListenerOptions withShutdownTimeout(Duration shutdownTimeout) {
        return new ListenerOptions(pollWait, maxBufferedRecords, initialBackoff, maxBackoff, maxAttempts, deadLetter,
                commitInterval, shutdownTimeout);
    }

    public Optional<StreamProducer> deadLetterProducer() {
        return Optional.ofNullable(deadLetter);
    }

    /** The retry delay after attempt {@code attempt} (1-based) failed. */
    Duration backoff(int attempt) {
        long millis = initialBackoff.toMillis() << Math.min(Math.max(attempt, 1) - 1, 30);
        return Duration.ofMillis(Math.min(Math.max(millis, 0), maxBackoff.toMillis()));
    }
}
