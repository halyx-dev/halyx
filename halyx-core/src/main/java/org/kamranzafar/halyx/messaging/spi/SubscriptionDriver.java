/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kamranzafar.halyx.messaging.spi;

import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.CloudException;

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

    /** Acks up to {@link #maxAckBatch()} messages in as few requests as the provider allows. */
    default void ackAll(List<DriverMessage> messages) {
        messages.forEach(this::ack);
    }

    /** Most messages per {@link #ackAll} request; 1 means the provider has no batch ack. */
    default int maxAckBatch() {
        return 1;
    }

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
