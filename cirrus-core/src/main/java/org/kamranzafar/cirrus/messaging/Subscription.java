package org.kamranzafar.cirrus.messaging;

import org.kamranzafar.cirrus.capability.Capabilities;

import java.time.Duration;
import java.util.List;

/** Consumes from one subscription or queue: pull with {@link #receive}, or run a listener with {@link #listen}. */
public interface Subscription {

    String provider();

    String name();

    Capabilities capabilities();

    /**
     * Up to {@code max} messages, waiting up to {@code wait} for one to arrive. Each must be acked, nacked or
     * left to its lease running out, after which it is redelivered.
     */
    List<ReceivedMessage> receive(int max, Duration wait);

    /** Runs {@code listener} on every message until the returned consumer is closed. */
    MessageConsumer listen(MessageListener listener, ConsumerOptions options);

    default MessageConsumer listen(MessageListener listener) {
        return listen(listener, ConsumerOptions.DEFAULTS);
    }

    /** How long a received message stays invisible without an extension. */
    Duration defaultLease();

    <T> T unwrap(Class<T> nativeType);
}
