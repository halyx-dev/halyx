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
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * The asynchronous view of a {@link StreamConsumer}, from {@link StreamConsumer#async()}: polling and committing
 * by hand as {@code CompletableFuture}s, so the caller never waits. A consumer may be used by one call at a time
 * (Kafka's client allows no more), so the calls of a consumer run one after another, in the order they were made,
 * each on the blocking executor: the view frees the caller's thread, not the consumer's. Listening is already
 * push-based: see {@link StreamConsumer#listen}.
 */
public interface AsyncStreamConsumer {

    String provider();

    String topic();

    String group();

    /** The next records from the partitions this member owns, waiting up to {@code wait}; see {@link StreamConsumer#poll}. */
    CompletableFuture<List<ReceivedRecord>> poll(Duration wait);

    /** Commits the position after every record polled so far; see {@link StreamConsumer#commit}. */
    CompletableFuture<Void> commit();

    /** The blocking consumer this is a view of. */
    StreamConsumer blocking();

    /**
     * A view that runs {@code consumer}'s calls one at a time on {@code executor}: for consumers without one of their
     * own. Calls through other views or the blocking consumer are not ordered with these.
     */
    static AsyncStreamConsumer onExecutor(StreamConsumer consumer, Executor executor) {
        Objects.requireNonNull(consumer, "consumer");
        Objects.requireNonNull(executor, "executor");
        Serial serial = new Serial();
        return new AsyncStreamConsumer() {
            @Override
            public String provider() {
                return consumer.provider();
            }

            @Override
            public String topic() {
                return consumer.topic();
            }

            @Override
            public String group() {
                return consumer.group();
            }

            @Override
            public CompletableFuture<List<ReceivedRecord>> poll(Duration wait) {
                return serial.submit(() -> consumer.poll(wait), executor);
            }

            @Override
            public CompletableFuture<Void> commit() {
                return serial.submit(() -> {
                    consumer.commit();
                    return null;
                }, executor);
            }

            @Override
            public StreamConsumer blocking() {
                return consumer;
            }
        };
    }

    /** Runs submitted calls one after another, each on its own executor, whether the one before failed or not. */
    final class Serial {

        private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);

        public synchronized <T> CompletableFuture<T> submit(Supplier<T> call, Executor executor) {
            CompletableFuture<T> next = tail.handle((done, error) -> null).thenApplyAsync(ignored -> call.get(), executor);
            tail = next;
            // The caller gets a copy: cancelling it must not start the next call while this one still runs.
            return next.copy();
        }
    }
}
