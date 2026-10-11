package org.kamranzafar.cirrus.azure.servicebus;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusMessageBatch;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import org.kamranzafar.cirrus.azure.AzureProvider;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.messaging.MessagingCapability;
import org.kamranzafar.cirrus.messaging.OutgoingMessage;
import org.kamranzafar.cirrus.messaging.spi.MessagingErrors;
import org.kamranzafar.cirrus.messaging.spi.PublisherDriver;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Sends to a Service Bus queue or topic. The message id is the deduplication id when given (Service Bus
 * drops repeats within the entity's duplicate-detection window, if enabled on it), otherwise a random UUID.
 * Delays become scheduled enqueue times. On session-enabled entities ({@code sessions}), ordering keys become
 * session ids, and messages without one get a unique session id, since such entities require one.
 */
public final class ServiceBusPublisherDriver implements PublisherDriver {

    private final ServiceBusSenderClient sender;
    private final String entity;
    private final long maxMessageBytes;
    private final boolean sessions;

    public ServiceBusPublisherDriver(ServiceBusSenderClient sender, String entity, long maxMessageBytes, boolean sessions) {
        this.sender = sender;
        this.entity = entity;
        this.maxMessageBytes = maxMessageBytes;
        this.sessions = sessions;
    }

    @Override
    public String provider() {
        return AzureProvider.ID;
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
        ServiceBusMessageBatch batch = sender.createMessageBatch();
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
                continue;
            }
            if (!batch.tryAddMessage(native_)) {
                send(batch);
                batch = sender.createMessageBatch();
                if (!batch.tryAddMessage(native_)) {
                    throw new InvalidArgumentException("Message of " + message.size() + " bytes does not fit in a "
                            + "Service Bus message", null, AzureProvider.ID, null);
                }
            }
        }
        send(batch);
        if (!scheduled.isEmpty()) {
            sender.sendMessages(scheduled);
        }
        return ids;
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
    public CloudException translate(Throwable error) {
        return MessagingErrors.translate(error, AzureProvider.ID, ServiceBusErrors::translate);
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return MessagingErrors.unwrap(nativeType, AzureProvider.ID, List.of(sender));
    }

    @Override
    public void close() {
        sender.close();
    }
}
