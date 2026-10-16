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

/**
 * Publishes CloudEvents to a cloud's event router: Amazon EventBridge, an Azure Event Grid namespace topic, or a
 * Google Cloud Eventarc Advanced message bus. The router's rules or subscriptions decide where each event goes;
 * receive them from the queue they deliver to with a {@code Subscription}, and {@link CloudEvents#decode}.
 * Thread-safe.
 */
public interface EventPublisher {

    String provider();

    /** The bus this publishes to. */
    String bus();

    /** Publishes one event; a retry after a failure keeps its id, so receivers can drop duplicates. */
    void publish(CloudEvent event);

    /**
     * Publishes events in as few requests as the router allows. Not atomic: if a request fails, the events of
     * earlier requests stay published; retrying the call is safe, as the events keep their ids.
     */
    void publishAll(List<CloudEvent> events);

    /** The native client, such as {@code EventBridgeClient}. */
    <T> T unwrap(Class<T> nativeType);
}
