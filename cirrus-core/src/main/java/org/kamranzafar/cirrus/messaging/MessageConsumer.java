package org.kamranzafar.cirrus.messaging;

/** A running listener on a subscription; {@link #close()} stops it. */
public interface MessageConsumer extends AutoCloseable {

    boolean isRunning();

    ConsumerStatistics statistics();

    /**
     * Stops pulling, lets running handlers finish (up to the shutdown timeout) and nacks any message received
     * but not yet started, so it is redelivered promptly.
     */
    @Override
    void close();
}
