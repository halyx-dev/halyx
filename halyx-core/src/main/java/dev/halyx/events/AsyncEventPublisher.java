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
package dev.halyx.events;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of an {@link EventPublisher}, from {@link EventPublisher#async()}: the same publishes and
 * failures, as {@code CompletableFuture}s. Where the provider's driver has a non-blocking path, no thread waits for
 * the network. Size checks, interceptors and error translation are those of the blocking calls.
 */
public interface AsyncEventPublisher {

    String provider();

    String bus();

    default CompletableFuture<Void> publish(CloudEvent event) {
        return publishAll(List.of(Objects.requireNonNull(event, "event")));
    }

    /**
     * Publishes in as few requests as the router allows, one after another. Not atomic, as for
     * {@link EventPublisher#publishAll}; retrying is safe, as the events keep their ids.
     */
    CompletableFuture<Void> publishAll(List<CloudEvent> events);

    /** The blocking publisher this is a view of. */
    EventPublisher blocking();

    /** A view that runs each of {@code publisher}'s blocking calls on {@code executor}: for publishers without one. */
    static AsyncEventPublisher onExecutor(EventPublisher publisher, Executor executor) {
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(executor, "executor");
        return new AsyncEventPublisher() {
            @Override
            public String provider() {
                return publisher.provider();
            }

            @Override
            public String bus() {
                return publisher.bus();
            }

            @Override
            public CompletableFuture<Void> publishAll(List<CloudEvent> events) {
                return CompletableFuture.runAsync(() -> publisher.publishAll(events), executor);
            }

            @Override
            public EventPublisher blocking() {
                return publisher;
            }
        };
    }
}
