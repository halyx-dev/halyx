package org.kamranzafar.cirrus.messaging;

/**
 * Counters of a running consumer.
 *
 * @param received      messages pulled
 * @param acked         messages handled successfully
 * @param nacked        messages returned for redelivery
 * @param failed        handler invocations that threw
 * @param inFlight      messages being handled now
 * @param receiveErrors failed receive requests (the consumer backs off and keeps polling)
 */
public record ConsumerStatistics(long received, long acked, long nacked, long failed, int inFlight, long receiveErrors) {
}
