package org.kamranzafar.cirrus.gcp.pubsub;

import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.gcp.GcpErrors;
import org.kamranzafar.cirrus.gcp.GcpProvider;
import org.kamranzafar.cirrus.messaging.MessagingCapability;
import org.kamranzafar.cirrus.messaging.OutgoingMessage;
import org.kamranzafar.cirrus.messaging.spi.MessagingErrors;
import org.kamranzafar.cirrus.messaging.spi.PublisherDriver;

import java.util.ArrayList;
import java.util.List;

/**
 * Publishes to a Pub/Sub topic with synchronous batch requests, so messages sharing an ordering key keep
 * their order within and across calls from one publisher. Ordering keys need a subscription with message
 * ordering enabled; Pub/Sub has no publish-time deduplication or delay.
 */
public final class PubSubPublisherDriver implements PublisherDriver {

    private final TopicAdminClient client;
    private final String topic;

    public PubSubPublisherDriver(TopicAdminClient client, String topic) {
        this.client = client;
        this.topic = topic;
    }

    @Override
    public String provider() {
        return GcpProvider.ID;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(MessagingCapability.ORDERING);
    }

    @Override
    public List<String> publish(List<OutgoingMessage> messages) {
        List<PubsubMessage> batch = new ArrayList<>(messages.size());
        for (OutgoingMessage message : messages) {
            var builder = PubsubMessage.newBuilder()
                    .setData(ByteString.copyFrom(message.bodyUnsafe()))
                    .putAllAttributes(message.attributes());
            message.orderingKey().ifPresent(builder::setOrderingKey);
            batch.add(builder.build());
        }
        return List.copyOf(client.publish(topic, batch).getMessageIdsList());
    }

    @Override
    public int maxBatchSize() {
        return 1000;
    }

    @Override
    public long maxMessageBytes() {
        return 10L * 1000 * 1000;
    }

    @Override
    public CloudException translate(Throwable error) {
        return MessagingErrors.translate(error, GcpProvider.ID, GcpErrors::translate);
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return MessagingErrors.unwrap(nativeType, GcpProvider.ID, List.of(client));
    }
}
