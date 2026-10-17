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
package dev.halyx.messaging;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of a {@link Publisher}, from {@link Publisher#async()}: the same publishes and failures, as
 * {@code CompletableFuture}s. Where the provider's driver has a non-blocking path (SNS and SQS do), no thread waits
 * for the network; claim-checked bodies are stored through the blob store's own asynchronous view. Checks,
 * interceptors and error translation are those of the blocking calls.
 */
public interface AsyncPublisher {

    String provider();

    String topic();

    default CompletableFuture<PublishResult> publish(OutgoingMessage message) {
        return publishAll(List.of(Objects.requireNonNull(message, "message"))).thenApply(List::getFirst);
    }

    default CompletableFuture<PublishResult> publish(String body) {
        return publish(OutgoingMessage.of(body));
    }

    /**
     * Publishes in as few requests as the provider allows, one after another in order. Not atomic, as for
     * {@link Publisher#publishAll}: if a request fails, the messages of earlier requests stay published.
     */
    CompletableFuture<List<PublishResult>> publishAll(List<OutgoingMessage> messages);

    /** The blocking publisher this is a view of. */
    Publisher blocking();

    /** A view that runs each of {@code publisher}'s blocking calls on {@code executor}: for publishers without one. */
    static AsyncPublisher onExecutor(Publisher publisher, Executor executor) {
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(executor, "executor");
        return new AsyncPublisher() {
            @Override
            public String provider() {
                return publisher.provider();
            }

            @Override
            public String topic() {
                return publisher.topic();
            }

            @Override
            public CompletableFuture<List<PublishResult>> publishAll(List<OutgoingMessage> messages) {
                return CompletableFuture.supplyAsync(() -> publisher.publishAll(messages), executor);
            }

            @Override
            public Publisher blocking() {
                return publisher;
            }
        };
    }
}
