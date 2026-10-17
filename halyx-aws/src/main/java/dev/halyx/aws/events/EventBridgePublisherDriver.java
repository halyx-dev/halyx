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
package dev.halyx.aws.events;

import dev.halyx.aws.AwsErrors;
import dev.halyx.aws.AwsProvider;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.events.spi.AbstractEventPublisherDriver;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Publishes to an EventBridge event bus with {@code PutEvents}: the CloudEvent's {@code source} and {@code type}
 * become the entry's {@code Source} and {@code DetailType}, so rules can match on them, and the whole event in the
 * CloudEvents JSON format is the {@code Detail}. A rule targeting an SQS queue delivers the EventBridge envelope,
 * which {@link CloudEvents#decode} turns back into the event.
 */
public final class EventBridgePublisherDriver extends AbstractEventPublisherDriver {

    /** EventBridge accepts 256 KB per request, all entries together; the rest of an entry needs some room. */
    static final long MAX_REQUEST_BYTES = 256 * 1024;
    static final long MAX_EVENT_BYTES = MAX_REQUEST_BYTES - 2048;

    private final EventBridgeClient client;
    private final String bus;

    public EventBridgePublisherDriver(EventBridgeClient client, String bus) {
        super(AwsProvider.ID);
        this.client = client;
        this.bus = bus;
    }

    @Override
    public String bus() {
        return bus;
    }

    @Override
    public void publish(List<CloudEvent> events) {
        // Split further when the entries of one batch together exceed the request limit.
        List<PutEventsRequestEntry> request = new ArrayList<>();
        long size = 0;
        for (CloudEvent event : events) {
            PutEventsRequestEntry entry = entry(event);
            long entrySize = entry.detail().getBytes(StandardCharsets.UTF_8).length + entry.source().length()
                    + entry.detailType().length() + 64;
            if (!request.isEmpty() && size + entrySize > MAX_REQUEST_BYTES) {
                send(request);
                request = new ArrayList<>();
                size = 0;
            }
            request.add(entry);
            size += entrySize;
        }
        if (!request.isEmpty()) {
            send(request);
        }
    }

    private PutEventsRequestEntry entry(CloudEvent event) {
        PutEventsRequestEntry.Builder entry = PutEventsRequestEntry.builder()
                .eventBusName(bus)
                .source(event.source())
                .detailType(event.type())
                .detail(CloudEvents.toJson(event));
        event.time().ifPresent(entry::time);
        return entry.build();
    }

    private void send(List<PutEventsRequestEntry> entries) {
        PutEventsResponse response = client.putEvents(r -> r.entries(entries));
        if (response.failedEntryCount() == null || response.failedEntryCount() == 0) {
            return;
        }
        // Report the first failure; the whole call may be retried, as the events keep their ids.
        List<PutEventsResultEntry> results = response.entries();
        for (int i = 0; i < results.size(); i++) {
            PutEventsResultEntry result = results.get(i);
            if (result.errorCode() != null) {
                throw AwsErrors.fromCode(result.errorCode(), response.failedEntryCount() + " of " + entries.size()
                        + " events not accepted by event bus " + bus + "; the first, with source "
                        + entries.get(i).source() + ": " + result.errorCode() + " " + result.errorMessage());
            }
        }
        throw new UnknownCloudException(response.failedEntryCount() + " events not accepted by event bus " + bus,
                null, AwsProvider.ID, null);
    }

    @Override
    public int maxBatchSize() {
        return 10;
    }

    @Override
    public long maxEventBytes() {
        return MAX_EVENT_BYTES;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AwsErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client);
    }
}
