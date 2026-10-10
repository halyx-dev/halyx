package org.kamranzafar.cirrus.messaging.spi;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;

import java.time.Duration;
import java.util.List;

/**
 * Pulls from one subscription or queue. Every received message stays invisible to other consumers for
 * the lease ({@link #defaultLease()}) unless acked, nacked or extended.
 */
public interface SubscriptionDriver extends AutoCloseable {

    String provider();

    String name();

    Capabilities capabilities();

    /**
     * Up to {@code max} messages, waiting up to {@code wait} for the first (long polling). Returns empty
     * if none arrived.
     */
    List<DriverMessage> receive(int max, Duration wait);

    /** Most messages per receive request. */
    int maxReceiveBatch();

    void ack(DriverMessage message);

    /** Makes the message available again, after {@code delay} where {@code NACK_DELAY} is supported. */
    void nack(DriverMessage message, Duration delay);

    /** Keeps the message invisible for {@code lease} from now. */
    void extendLease(DriverMessage message, Duration lease);

    /** How long a received message stays invisible without an extension. */
    Duration defaultLease();

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    @Override
    default void close() {
    }
}
