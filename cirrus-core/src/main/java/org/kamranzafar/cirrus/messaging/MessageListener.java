package org.kamranzafar.cirrus.messaging;

/**
 * Handles one message. Returning normally acks it; throwing nacks it for redelivery after a backoff. A
 * listener may also settle the message itself ({@link ReceivedMessage#ack()}, {@code nack}), in which case
 * the consumer leaves it alone. Delivery is at least once: make handling idempotent.
 */
@FunctionalInterface
public interface MessageListener {

    void onMessage(ReceivedMessage message) throws Exception;
}
