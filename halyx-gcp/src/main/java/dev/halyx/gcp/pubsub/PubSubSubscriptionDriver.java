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
package dev.halyx.gcp.pubsub;

import com.google.api.gax.grpc.GrpcCallContext;
import com.google.api.gax.rpc.DeadlineExceededException;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.ReceivedMessage;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.gcp.GcpErrors;
import dev.halyx.gcp.GcpProvider;
import dev.halyx.messaging.MessagingCapability;
import dev.halyx.messaging.spi.AbstractSubscriptionDriver;
import dev.halyx.messaging.spi.DriverMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Pulls from a Pub/Sub subscription. The lease is the subscription's ack deadline; nacks and extensions
 * set a new deadline with ModifyAckDeadline (a nack with a delay redelivers after it). The delivery attempt
 * is only reported when the subscription has a dead-letter policy, and is 0 otherwise.
 */
public final class PubSubSubscriptionDriver extends AbstractSubscriptionDriver {

    /** Pub/Sub ack deadlines are at most 10 minutes. */
    private static final Duration MAX_DEADLINE = Duration.ofMinutes(10);

    private final SubscriptionAdminClient client;
    private final String subscription;
    private final Duration lease;

    public PubSubSubscriptionDriver(SubscriptionAdminClient client, String subscription) {
        super(GcpProvider.ID);
        this.client = client;
        this.subscription = subscription;
        int deadline;
        try {
            deadline = client.getSubscription(subscription).getAckDeadlineSeconds();
        } catch (RuntimeException e) {
            throw GcpErrors.translate(e).orElseThrow(() -> e);
        }
        this.lease = Duration.ofSeconds(deadline > 0 ? deadline : 10);
    }

    @Override
    public String name() {
        return subscription;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(MessagingCapability.ORDERING, MessagingCapability.NACK_DELAY);
    }

    @Override
    public List<DriverMessage> receive(int max, Duration wait) {
        var request = PullRequest.newBuilder().setSubscription(subscription).setMaxMessages(max).build();
        List<ReceivedMessage> received;
        try {
            // Pull waits for messages; bound it by the caller's wait.
            received = client.pullCallable()
                    .call(request, GrpcCallContext.createDefault().withTimeoutDuration(wait.plusSeconds(1)))
                    .getReceivedMessagesList();
        } catch (DeadlineExceededException e) {
            return List.of();
        }
        List<DriverMessage> messages = new ArrayList<>(received.size());
        for (ReceivedMessage m : received) {
            var message = m.getMessage();
            Instant published = message.hasPublishTime()
                    ? Instant.ofEpochSecond(message.getPublishTime().getSeconds(), message.getPublishTime().getNanos())
                    : null;
            messages.add(new DriverMessage(message.getMessageId(), m.getAckId(), message.getData().toByteArray(),
                    message.getAttributesMap(), message.getOrderingKey().isEmpty() ? null : message.getOrderingKey(),
                    published, m.getDeliveryAttempt()));
        }
        return messages;
    }

    @Override
    public int maxReceiveBatch() {
        return 1000;
    }

    @Override
    public void ack(DriverMessage message) {
        client.acknowledge(subscription, List.of(message.receipt()));
    }

    @Override
    public void ackAll(List<DriverMessage> messages) {
        client.acknowledge(subscription, messages.stream().map(DriverMessage::receipt).toList());
    }

    @Override
    public int maxAckBatch() {
        return 100;
    }

    @Override
    public void nack(DriverMessage message, Duration delay) {
        modifyDeadline(message, delay);
    }

    @Override
    public void extendLease(DriverMessage message, Duration lease) {
        modifyDeadline(message, lease);
    }

    private void modifyDeadline(DriverMessage message, Duration deadline) {
        long seconds = Math.min(MAX_DEADLINE.toSeconds(), Math.max(0, deadline.toSeconds()));
        client.modifyAckDeadline(subscription, List.of(message.receipt()), (int) seconds);
    }

    @Override
    public Duration defaultLease() {
        return lease;
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
