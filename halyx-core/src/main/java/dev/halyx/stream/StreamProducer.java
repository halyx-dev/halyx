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

import dev.halyx.interceptor.InterceptorChain;

import dev.halyx.capability.Capabilities;

import java.util.List;
import java.util.concurrent.Executor;

/** Sends records to one topic. Thread-safe; share one per topic. */
public interface StreamProducer {

    String provider();

    String topic();

    Capabilities capabilities();

    /** Sends a record and waits until the broker has stored it. */
    SendResult send(OutgoingRecord record);

    default SendResult send(String key, String value) {
        return send(OutgoingRecord.of(key, value));
    }

    /**
     * Sends records and waits until the broker has stored all of them; results are in the same order. Records
     * with the same key keep their order. Unless the producer is transactional, a failure can leave some of
     * the records stored.
     */
    List<SendResult> sendAll(List<OutgoingRecord> records);

    /** How many partitions the topic has; a call to the cluster, which also checks that the topic exists. */
    int partitionCount();

    /** The native client, such as Kafka's {@code Producer}. */
    /**
     * The asynchronous view of this producer, whose sends return {@code CompletableFuture}s that complete when the
     * broker acknowledges, with no thread waiting. Cheap to call.
     */
    default AsyncStreamProducer async() {
        return async(InterceptorChain.blocking());
    }

    /** The asynchronous view, running any blocking work on {@code blocking}, such as an application's scheduler. */
    default AsyncStreamProducer async(Executor blocking) {
        return AsyncStreamProducer.onExecutor(this, blocking);
    }

    <T> T unwrap(Class<T> nativeType);
}
