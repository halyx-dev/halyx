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
package dev.halyx.gcp.eventarc;

import com.google.cloud.eventarc.publishing.v1.PublishRequest;
import com.google.cloud.eventarc.publishing.v1.PublisherClient;
import dev.halyx.error.CloudException;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.events.spi.AbstractEventPublisherDriver;
import dev.halyx.gcp.GcpErrors;
import dev.halyx.gcp.GcpProvider;

import java.util.List;
import java.util.Optional;

/**
 * Publishes to an Eventarc Advanced message bus, one event per request in the CloudEvents JSON format. The bus's
 * enrollments and pipelines route events; a pipeline with a Pub/Sub destination delivers them to a topic, whose
 * subscriptions Halyx receives from.
 */
public final class EventarcPublisherDriver extends AbstractEventPublisherDriver {

    /** Eventarc accepts messages up to 1 MB; the request needs some room besides the event. */
    static final long MAX_EVENT_BYTES = 1024 * 1024 - 4096;

    private final PublisherClient client;
    private final String bus;

    /** @param bus the message bus's resource name, {@code projects/<p>/locations/<l>/messageBuses/<id>} */
    public EventarcPublisherDriver(PublisherClient client, String bus) {
        super(GcpProvider.ID);
        this.client = client;
        this.bus = bus;
    }

    @Override
    public String bus() {
        return bus;
    }

    @Override
    public void publish(List<CloudEvent> events) {
        for (CloudEvent event : events) {
            client.publish(PublishRequest.newBuilder().setMessageBus(bus).setJsonMessage(CloudEvents.toJson(event)).build());
        }
    }

    @Override
    public int maxBatchSize() {
        return 1;
    }

    @Override
    public long maxEventBytes() {
        return MAX_EVENT_BYTES;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return GcpErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client);
    }
}
