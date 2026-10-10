package org.kamranzafar.cirrus.messaging.spi;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.messaging.OutgoingMessage;

import java.util.List;

/** Publishes to one topic or queue. Core splits batches to {@link #maxBatchSize()} and checks capabilities. */
public interface PublisherDriver extends AutoCloseable {

    String provider();

    String topic();

    Capabilities capabilities();

    /** Publishes in one request where the provider allows; returns the message ids in order. */
    List<String> publish(List<OutgoingMessage> messages);

    /** Most messages per publish request (SNS and SQS: 10; Service Bus and Pub/Sub: more). */
    int maxBatchSize();

    /** Largest body the provider accepts, before attributes. */
    long maxMessageBytes();

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    @Override
    default void close() {
    }
}
