package org.kamranzafar.cirrus.messaging;

import org.kamranzafar.cirrus.capability.Capabilities;

import java.util.List;

/** Publishes to one topic or queue. Thread-safe; share one per topic. */
public interface Publisher {

    String provider();

    String topic();

    Capabilities capabilities();

    PublishResult publish(OutgoingMessage message);

    default PublishResult publish(String body) {
        return publish(OutgoingMessage.of(body));
    }

    /**
     * Publishes in as few requests as the provider allows (batches of 10 on SNS and SQS). If a batch
     * fails, earlier batches stay published.
     */
    List<PublishResult> publishAll(List<OutgoingMessage> messages);

    /** The native client, such as {@code SnsClient}. */
    <T> T unwrap(Class<T> nativeType);
}
