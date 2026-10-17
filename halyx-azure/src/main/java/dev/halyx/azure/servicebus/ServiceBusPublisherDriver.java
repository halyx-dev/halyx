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
package dev.halyx.azure.servicebus;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusMessageBatch;
import com.azure.messaging.servicebus.ServiceBusSenderAsyncClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import dev.halyx.azure.AzureProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.messaging.MessagingCapability;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.spi.AbstractPublisherDriver;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Sends to a Service Bus queue or topic. The message id is the deduplication id when given (Service Bus
 * drops repeats within the entity's duplicate-detection window, if enabled on it), otherwise a random UUID.
 * Delays become scheduled enqueue times. On session-enabled entities ({@code sessions}), ordering keys become
 * session ids, and messages without one get a unique session id, since such entities require one.
 */
public final class ServiceBusPublisherDriver extends AbstractPublisherDriver {

    private final ServiceBusSenderClient sender;
    private final Supplier<ServiceBusSenderAsyncClient> asyncSenders;
    private ServiceBusSenderAsyncClient asyncSender;
    private final String entity;
    private final long maxMessageBytes;
    private final boolean sessions;

    /** @param asyncSenders makes the non-blocking sender, on the first asynchronous publish */
    public ServiceBusPublisherDriver(ServiceBusSenderClient sender, Supplier<ServiceBusSenderAsyncClient> asyncSenders,
                                     String entity, long maxMessageBytes, boolean sessions) {
        super(AzureProvider.ID);
        this.sender = sender;
        this.asyncSenders = asyncSenders;
        this.entity = entity;
        this.maxMessageBytes = maxMessageBytes;
        this.sessions = sessions;
    }

    @Override
    public String topic() {
        return entity;
    }

    @Override
    public Capabilities capabilities() {
        return sessions
                ? Capabilities.of(MessagingCapability.ORDERING, MessagingCapability.DEDUPLICATION, MessagingCapability.DELAYED_DELIVERY)
                : Capabilities.of(MessagingCapability.DEDUPLICATION, MessagingCapability.DELAYED_DELIVERY);
    }

    @Override
    public List<String> publish(List<OutgoingMessage> messages) {
        List<String> ids = new ArrayList<>(messages.size());
        List<ServiceBusMessage> scheduled = new ArrayList<>();
        List<ServiceBusMessage> immediate = toNative(messages, ids, scheduled);
        ServiceBusMessageBatch batch = sender.createMessageBatch();
        for (ServiceBusMessage message : immediate) {
            if (!batch.tryAddMessage(message)) {
                send(batch);
                batch = sender.createMessageBatch();
                if (!batch.tryAddMessage(message)) {
                    throw tooLarge(message);
                }
            }
        }
        send(batch);
        if (!scheduled.isEmpty()) {
            sender.sendMessages(scheduled);
        }
        return ids;
    }

    /** The same batches without blocking, each sent before the next is filled, then the scheduled messages. */
    @Override
    public CompletableFuture<List<String>> publishAsync(List<OutgoingMessage> messages, Executor blocking) {
        List<String> ids = new ArrayList<>(messages.size());
        List<ServiceBusMessage> scheduled = new ArrayList<>();
        List<ServiceBusMessage> immediate = toNative(messages, ids, scheduled);
        ServiceBusSenderAsyncClient async = asyncSender();
        return sendFrom(async, immediate, 0)
                .thenCompose(done -> scheduled.isEmpty() ? CompletableFuture.completedFuture(null)
                        : async.sendMessages(scheduled).toFuture())
                .thenApply(done -> ids);
    }

    private CompletableFuture<Void> sendFrom(ServiceBusSenderAsyncClient async, List<ServiceBusMessage> messages, int from) {
        if (from >= messages.size()) {
            return CompletableFuture.completedFuture(null);
        }
        return async.createMessageBatch().toFuture().thenCompose(batch -> {
            int next = from;
            while (next < messages.size() && batch.tryAddMessage(messages.get(next))) {
                next++;
            }
            if (next == from) {
                return CompletableFuture.failedFuture(tooLarge(messages.get(from)));
            }
            int sent = next;
            return async.sendMessages(batch).toFuture().thenCompose(done -> sendFrom(async, messages, sent));
        });
    }

    /** The messages to send now, in order; delayed ones go to {@code scheduled}, and every id to {@code ids}. */
    private List<ServiceBusMessage> toNative(List<OutgoingMessage> messages, List<String> ids, List<ServiceBusMessage> scheduled) {
        List<ServiceBusMessage> immediate = new ArrayList<>(messages.size());
        for (OutgoingMessage message : messages) {
            String id = message.deduplicationId().orElseGet(() -> UUID.randomUUID().toString());
            ids.add(id);
            ServiceBusMessage native_ = new ServiceBusMessage(BinaryData.fromBytes(message.bodyUnsafe())).setMessageId(id);
            message.attributes().forEach((k, v) -> native_.getApplicationProperties().put(k, v));
            if (sessions) {
                native_.setSessionId(message.orderingKey().orElseGet(() -> UUID.randomUUID().toString()));
            }
            if (message.delay().isPresent()) {
                native_.setScheduledEnqueueTime(OffsetDateTime.now().plus(message.delay().get()));
                scheduled.add(native_);
            } else {
                immediate.add(native_);
            }
        }
        return immediate;
    }

    private static InvalidArgumentException tooLarge(ServiceBusMessage message) {
        return new InvalidArgumentException("Message of " + message.getBody().toBytes().length + " bytes does not fit in a "
                + "Service Bus message", null, AzureProvider.ID, null);
    }

    private synchronized ServiceBusSenderAsyncClient asyncSender() {
        if (asyncSender == null) {
            asyncSender = asyncSenders.get();
        }
        return asyncSender;
    }

    private void send(ServiceBusMessageBatch batch) {
        if (batch.getCount() > 0) {
            sender.sendMessages(batch);
        }
    }

    @Override
    public int maxBatchSize() {
        return 100;
    }

    @Override
    public long maxMessageBytes() {
        return maxMessageBytes;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return ServiceBusErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(sender);
    }

    @Override
    public void close() {
        sender.close();
        synchronized (this) {
            if (asyncSender != null) {
                asyncSender.close();
                asyncSender = null;
            }
        }
    }
}
