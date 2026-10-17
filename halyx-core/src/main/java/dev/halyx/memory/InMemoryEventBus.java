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
package dev.halyx.memory;

import dev.halyx.error.CloudException;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.events.spi.AbstractEventPublisherDriver;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.spi.PublisherDriver;

import java.util.List;
import java.util.Optional;

/**
 * An event bus as an in-memory topic of the same name: each event is published as CloudEvents JSON, so
 * subscriptions on that topic receive it, as a router's rule delivers events to a queue.
 */
final class InMemoryEventBus extends AbstractEventPublisherDriver {

    private final String bus;
    private final PublisherDriver topic;

    InMemoryEventBus(String bus, PublisherDriver topic) {
        super(InMemoryProvider.ID);
        this.bus = bus;
        this.topic = topic;
    }

    @Override
    public String bus() {
        return bus;
    }

    @Override
    public void publish(List<CloudEvent> events) {
        topic.publish(events.stream()
                .map(event -> OutgoingMessage.builder(CloudEvents.toJson(event))
                        .attribute("content-type", CloudEvents.JSON_CONTENT_TYPE).build())
                .toList());
    }

    @Override
    public int maxBatchSize() {
        return 100;
    }

    @Override
    public long maxEventBytes() {
        return 1024 * 1024;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return Optional.empty();
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of();
    }
}
