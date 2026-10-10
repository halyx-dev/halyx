package org.kamranzafar.cirrus.messaging;

import org.kamranzafar.cirrus.capability.Capability;

/** Messaging features that differ between providers. */
public enum MessagingCapability implements Capability {
    /**
     * {@link OutgoingMessage#orderingKey()}: messages with the same key are delivered in publish order, one
     * at a time (SQS FIFO message groups, Pub/Sub ordering keys).
     */
    ORDERING,
    /** {@link OutgoingMessage#deduplicationId()}: a repeat of a recent id is dropped (SQS FIFO, Service Bus). */
    DEDUPLICATION,
    /** {@link OutgoingMessage#delay()}: delivery held back for a while (SQS up to 15 minutes, Service Bus). */
    DELAYED_DELIVERY,
    /** {@code nack(delay)} holds the message back for the delay rather than redelivering it at once. */
    NACK_DELAY
}
