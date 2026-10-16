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
package dev.halyx.azure.eventgrid;

import com.azure.messaging.eventgrid.namespaces.EventGridSenderClient;
import dev.halyx.azure.AzureErrors;
import dev.halyx.azure.AzureProvider;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.events.spi.EventPublisherDriver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Publishes to an Event Grid namespace topic, natively in CloudEvents. Its subscriptions deliver the events, by
 * pull ({@link EventGridSubscriptionDriver}) or push.
 */
public final class EventGridPublisherDriver implements EventPublisherDriver {

    /** Event Grid takes 1 MB per request and per event; the rest of the request needs some room. */
    static final long MAX_REQUEST_BYTES = 1024 * 1024;
    static final long MAX_EVENT_BYTES = MAX_REQUEST_BYTES - 4096;

    private final EventGridSenderClient client;
    private final String topic;

    public EventGridPublisherDriver(EventGridSenderClient client, String topic) {
        this.client = client;
        this.topic = topic;
    }

    @Override
    public String provider() {
        return AzureProvider.ID;
    }

    @Override
    public String bus() {
        return topic;
    }

    @Override
    public void publish(List<CloudEvent> events) {
        List<com.azure.core.models.CloudEvent> request = new ArrayList<>();
        long size = 0;
        for (CloudEvent event : events) {
            long eventSize = CloudEvents.toJson(event).getBytes(StandardCharsets.UTF_8).length;
            if (!request.isEmpty() && size + eventSize > MAX_REQUEST_BYTES) {
                client.send(request);
                request = new ArrayList<>();
                size = 0;
            }
            request.add(EventGridEvents.toAzure(event));
            size += eventSize;
        }
        if (!request.isEmpty()) {
            client.send(request);
        }
    }

    @Override
    public int maxBatchSize() {
        return 100;
    }

    @Override
    public long maxEventBytes() {
        return MAX_EVENT_BYTES;
    }

    @Override
    public CloudException translate(Throwable error) {
        if (error instanceof CloudException cloud) {
            return cloud;
        }
        return AzureErrors.translate(error).orElseGet(() -> new UnknownCloudException(error.getMessage(), error,
                AzureProvider.ID, null));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        if (nativeType.isInstance(client)) {
            return nativeType.cast(client);
        }
        throw new IllegalArgumentException("Event Grid publishers unwrap to EventGridSenderClient, not " + nativeType.getName());
    }
}
