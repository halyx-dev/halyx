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
package dev.halyx.messaging.spi;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.messaging.OutgoingMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Publishes to one topic or queue. Core splits batches to {@link #maxBatchSize()} and checks capabilities. */
public interface PublisherDriver extends AutoCloseable {

    String provider();

    String topic();

    Capabilities capabilities();

    /** Publishes in one request where the provider allows; returns the message ids in order. */
    List<String> publish(List<OutgoingMessage> messages);

    /** Most messages per publish request (SNS and SQS: 10; Service Bus and Pub/Sub: more). */
    int maxBatchSize();

    /** Largest body the provider accepts, before attributes. */
    long maxMessageBytes();

    /**
     * {@link #publish} without blocking, where the SDK allows it; by default the blocking call on the blocking
     * executor. Failures may be returned or thrown; the API layer translates both.
     */
    default CompletableFuture<List<String>> publishAsync(List<OutgoingMessage> messages, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> publish(messages), blocking);
    }

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    @Override
    default void close() {
    }
}
