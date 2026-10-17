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
import com.google.pubsub.v1.AcknowledgeRequest;
import com.google.pubsub.v1.ModifyAckDeadlineRequest;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.ReceivedMessage;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.gcp.GcpErrors;
import dev.halyx.gcp.GcpFutures;
import dev.halyx.gcp.GcpProvider;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.messaging.MessagingCapability;
import dev.halyx.messaging.spi.AbstractSubscriptionDriver;
import dev.halyx.messaging.spi.DriverMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
        return toDriverMessages(received);
    }

    /** The pull without a waiting thread; a pull that times out with nothing to deliver gives no messages. */
    @Override
    public CompletableFuture<List<DriverMessage>> receiveAsync(int max, Duration wait, Executor blocking) {
        var request = PullRequest.newBuilder().setSubscription(subscription).setMaxMessages(max).build();
        return GcpFutures.of(client.pullCallable().futureCall(request,
                        GrpcCallContext.createDefault().withTimeoutDuration(wait.plusSeconds(1))))
                .thenApply(response -> toDriverMessages(response.getReceivedMessagesList()))
                .exceptionallyCompose(error -> AsyncInvocation.unwrap(error) instanceof DeadlineExceededException
                        ? CompletableFuture.completedFuture(List.of())
                        : CompletableFuture.failedFuture(error));
    }

    @Override
    public CompletableFuture<Void> ackAsync(DriverMessage message, Executor blocking) {
        return GcpFutures.of(client.acknowledgeCallable().futureCall(AcknowledgeRequest.newBuilder()
                .setSubscription(subscription).addAckIds(message.receipt()).build())).thenApply(r -> null);
    }

    @Override
    public CompletableFuture<Void> nackAsync(DriverMessage message, Duration delay, Executor blocking) {
        return modifyDeadlineAsync(message, delay);
    }

    @Override
    public CompletableFuture<Void> extendLeaseAsync(DriverMessage message, Duration lease, Executor blocking) {
        return modifyDeadlineAsync(message, lease);
    }

    private CompletableFuture<Void> modifyDeadlineAsync(DriverMessage message, Duration deadline) {
        long seconds = Math.min(MAX_DEADLINE.toSeconds(), Math.max(0, deadline.toSeconds()));
        return GcpFutures.of(client.modifyAckDeadlineCallable().futureCall(ModifyAckDeadlineRequest.newBuilder()
                .setSubscription(subscription).addAckIds(message.receipt()).setAckDeadlineSeconds((int) seconds).build()))
                .thenApply(r -> null);
    }

    private static List<DriverMessage> toDriverMessages(List<ReceivedMessage> received) {
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
