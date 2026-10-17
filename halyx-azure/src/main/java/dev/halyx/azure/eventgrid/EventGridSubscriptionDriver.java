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

import com.azure.messaging.eventgrid.namespaces.EventGridReceiverAsyncClient;
import com.azure.messaging.eventgrid.namespaces.EventGridReceiverClient;
import com.azure.messaging.eventgrid.namespaces.models.FailedLockToken;
import com.azure.messaging.eventgrid.namespaces.models.ReceiveDetails;
import com.azure.messaging.eventgrid.namespaces.models.ReleaseDelay;
import dev.halyx.azure.AzureErrors;
import dev.halyx.azure.AzureProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.NotFoundException;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.messaging.MessagingCapability;
import dev.halyx.messaging.spi.AbstractSubscriptionDriver;
import dev.halyx.messaging.spi.DriverMessage;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Receives from an Event Grid namespace topic's pull subscription. Each event arrives as a message holding the
 * event in the CloudEvents JSON format, which {@link CloudEvents#decode} reads; its receipt is the lock token. A
 * nack releases the event after the longest delay Event Grid offers that isn't longer than asked for (none, 10s,
 * 1m, 10m or 1h), and extending the lease renews the lock for the subscription's lock duration.
 */
public final class EventGridSubscriptionDriver extends AbstractSubscriptionDriver {

    /** Event Grid waits at least 10 and at most 120 seconds for events to arrive. */
    static final Duration MIN_WAIT = Duration.ofSeconds(10);
    static final Duration MAX_WAIT = Duration.ofSeconds(120);

    private static final List<Map.Entry<Duration, ReleaseDelay>> RELEASE_DELAYS = List.of(
            Map.entry(Duration.ofHours(1), ReleaseDelay.ONE_HOUR),
            Map.entry(Duration.ofMinutes(10), ReleaseDelay.TEN_MINUTES),
            Map.entry(Duration.ofMinutes(1), ReleaseDelay.ONE_MINUTE),
            Map.entry(Duration.ofSeconds(10), ReleaseDelay.TEN_SECONDS));

    private final EventGridReceiverClient client;
    private final EventGridReceiverAsyncClient asyncClient;
    private final String name;
    private final Duration lockDuration;

    /**
     * @param lockDuration the subscription's lock duration (Event Grid's default is 60 seconds)
     */
    public EventGridSubscriptionDriver(EventGridReceiverClient client, EventGridReceiverAsyncClient asyncClient, String name,
                                       Duration lockDuration) {
        super(AzureProvider.ID);
        this.client = client;
        this.asyncClient = asyncClient;
        this.name = name;
        this.lockDuration = lockDuration;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(MessagingCapability.NACK_DELAY);
    }

    @Override
    public List<DriverMessage> receive(int max, Duration wait) {
        return toDriverMessages(client.receive(Math.min(max, maxReceiveBatch()), clamped(wait)).getDetails());
    }

    /** The receive without a waiting thread. */
    @Override
    public CompletableFuture<List<DriverMessage>> receiveAsync(int max, Duration wait, Executor blocking) {
        return asyncClient.receive(Math.min(max, maxReceiveBatch()), clamped(wait)).toFuture()
                .thenApply(result -> toDriverMessages(result.getDetails()));
    }

    @Override
    public CompletableFuture<Void> ackAsync(DriverMessage message, Executor blocking) {
        return asyncClient.acknowledge(List.of(message.receipt())).toFuture()
                .thenAccept(result -> check(result.getFailedLockTokens(), "acknowledge"));
    }

    @Override
    public CompletableFuture<Void> nackAsync(DriverMessage message, Duration delay, Executor blocking) {
        return asyncClient.release(List.of(message.receipt()), releaseDelay(delay)).toFuture()
                .thenAccept(result -> check(result.getFailedLockTokens(), "release"));
    }

    @Override
    public CompletableFuture<Void> extendLeaseAsync(DriverMessage message, Duration lease, Executor blocking) {
        return asyncClient.renewLocks(List.of(message.receipt())).toFuture()
                .thenAccept(result -> check(result.getFailedLockTokens(), "renew the lock of"));
    }

    private static Duration clamped(Duration wait) {
        return wait.compareTo(MIN_WAIT) < 0 ? MIN_WAIT : wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait;
    }

    private static List<DriverMessage> toDriverMessages(List<ReceiveDetails> received) {
        List<DriverMessage> messages = new ArrayList<>();
        for (ReceiveDetails details : received) {
            CloudEvent event = EventGridEvents.fromAzure(details.getEvent());
            messages.add(new DriverMessage(event.id(), details.getBrokerProperties().getLockToken(),
                    CloudEvents.toJson(event).getBytes(StandardCharsets.UTF_8),
                    Map.of("content-type", CloudEvents.JSON_CONTENT_TYPE), null, event.time().orElse(null),
                    details.getBrokerProperties().getDeliveryCount()));
        }
        return messages;
    }

    @Override
    public int maxReceiveBatch() {
        return 100;
    }

    @Override
    public void ack(DriverMessage message) {
        ackAll(List.of(message));
    }

    @Override
    public void ackAll(List<DriverMessage> messages) {
        check(client.acknowledge(messages.stream().map(DriverMessage::receipt).toList()).getFailedLockTokens(), "acknowledge");
    }

    @Override
    public int maxAckBatch() {
        return 100;
    }

    @Override
    public void nack(DriverMessage message, Duration delay) {
        check(client.release(List.of(message.receipt()), releaseDelay(delay)).getFailedLockTokens(), "release");
    }

    /** The longest of Event Grid's release delays that {@code delay} reaches. */
    private static ReleaseDelay releaseDelay(Duration delay) {
        for (Map.Entry<Duration, ReleaseDelay> option : RELEASE_DELAYS) {
            if (delay.compareTo(option.getKey()) >= 0) {
                return option.getValue();
            }
        }
        return ReleaseDelay.NO_DELAY;
    }

    @Override
    public void extendLease(DriverMessage message, Duration lease) {
        check(client.renewLocks(List.of(message.receipt())).getFailedLockTokens(), "renew the lock of");
    }

    @Override
    public Duration defaultLease() {
        return lockDuration;
    }

    private void check(List<FailedLockToken> failed, String action) {
        if (failed != null && !failed.isEmpty()) {
            FailedLockToken first = failed.getFirst();
            String code = first.getError() == null ? null : first.getError().getCode();
            // A lock that expired or was already settled can no longer be used, as with other providers' receipts.
            throw new NotFoundException("Could not " + action + " " + failed.size() + " event(s) on " + name
                    + ": " + (first.getError() == null ? "unknown error" : first.getError().getMessage()),
                    null, AzureProvider.ID, code);
        }
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AzureErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(client);
    }
}
