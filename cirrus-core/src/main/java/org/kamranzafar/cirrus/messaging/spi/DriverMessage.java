package org.kamranzafar.cirrus.messaging.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A message as a subscription driver received it.
 *
 * @param id              the provider's message id
 * @param receipt         what the driver needs to ack, nack or extend this delivery (an SQS receipt handle,
 *                        a Pub/Sub ack id, a Service Bus lock token)
 * @param body            the raw body
 * @param attributes      string attributes
 * @param orderingKey     the ordering key, or null
 * @param publishTime     when the message was published, or null if unknown
 * @param deliveryAttempt 1 on the first delivery, then counting redeliveries; 0 if the provider doesn't say
 */
public record DriverMessage(String id, String receipt, byte[] body, Map<String, String> attributes,
                            String orderingKey, Instant publishTime, int deliveryAttempt) {

    public DriverMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(body, "body");
        attributes = Map.copyOf(attributes);
    }
}
