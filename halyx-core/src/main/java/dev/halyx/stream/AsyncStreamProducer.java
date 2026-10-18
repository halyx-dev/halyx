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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of a {@link StreamProducer}, from {@link StreamProducer#async()}: sends as
 * {@code CompletableFuture}s that complete when the broker acknowledges, with no thread waiting. Header checks, claim
 * checks (through the blob store's asynchronous view), interceptors and error translation are those of the blocking
 * sends. Kafka's client may still block a send briefly while it fetches a topic's metadata the first time, or when its
 * buffer is full, up to {@code max.block.ms}. Transactional producers send each batch in a transaction on the
 * blocking executor.
 */
public interface AsyncStreamProducer {

    String provider();

    String topic();

    default CompletableFuture<SendResult> send(OutgoingRecord record) {
        return sendAll(List.of(Objects.requireNonNull(record, "record"))).thenApply(List::getFirst);
    }

    default CompletableFuture<SendResult> send(String key, String value) {
        return send(OutgoingRecord.of(key, value));
    }

    /** All the records, in order; they share a transaction on a transactional producer. */
    CompletableFuture<List<SendResult>> sendAll(List<OutgoingRecord> records);

    /** The blocking producer this is a view of. */
    StreamProducer blocking();

    /** A view that runs each of {@code producer}'s blocking sends on {@code executor}: for producers without one. */
    static AsyncStreamProducer onExecutor(StreamProducer producer, Executor executor) {
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(executor, "executor");
        return new AsyncStreamProducer() {
            @Override
            public String provider() {
                return producer.provider();
            }

            @Override
            public String topic() {
                return producer.topic();
            }

            @Override
            public CompletableFuture<List<SendResult>> sendAll(List<OutgoingRecord> records) {
                return CompletableFuture.supplyAsync(() -> producer.sendAll(records), executor);
            }

            @Override
            public StreamProducer blocking() {
                return producer;
            }
        };
    }
}
