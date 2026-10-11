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
package org.kamranzafar.cirrus.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** A delivered message. Settle it exactly once with {@link #ack()} or {@link #nack}. */
public final class ReceivedMessage {

    /** What settles a delivery; implemented by the subscription. */
    interface Settlement {
        void ack();

        /** Acks in a later batch; a lost ack only means a redelivery. */
        default void ackDeferred() {
            ack();
        }

        void nack(Duration delay);

        void extendLease(Duration lease);
    }

    private final String id;
    private final byte[] body;
    private final Map<String, String> attributes;
    private final String orderingKey;
    private final Instant publishTime;
    private final int deliveryAttempt;
    private final Settlement settlement;
    private final AtomicBoolean settled = new AtomicBoolean();

    ReceivedMessage(String id, byte[] body, Map<String, String> attributes, String orderingKey, Instant publishTime,
                    int deliveryAttempt, Settlement settlement) {
        this.id = id;
        this.body = body;
        this.attributes = Map.copyOf(attributes);
        this.orderingKey = orderingKey;
        this.publishTime = publishTime;
        this.deliveryAttempt = deliveryAttempt;
        this.settlement = Objects.requireNonNull(settlement, "settlement");
    }

    public String id() {
        return id;
    }

    public byte[] body() {
        return body.clone();
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public Optional<String> attribute(String name) {
        return Optional.ofNullable(attributes.get(name));
    }

    public Optional<String> orderingKey() {
        return Optional.ofNullable(orderingKey);
    }

    public Optional<Instant> publishTime() {
        return Optional.ofNullable(publishTime);
    }

    /** 1 on the first delivery, then counting redeliveries; 0 if the provider doesn't report it. */
    public int deliveryAttempt() {
        return deliveryAttempt;
    }

    public boolean isSettled() {
        return settled.get();
    }

    /** Done: the message won't be delivered again. */
    public void ack() {
        settle();
        settlement.ack();
    }

    /** Settles now and acks with the next batch, as listeners do; failures are logged, not thrown. */
    void ackDeferred() {
        settle();
        settlement.ackDeferred();
    }

    /** Redeliver as soon as possible. */
    public void nack() {
        nack(Duration.ZERO);
    }

    /** Redeliver after {@code delay} (immediately on providers without {@link MessagingCapability#NACK_DELAY}). */
    public void nack(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        settle();
        settlement.nack(delay);
    }

    /** Keeps the message invisible for {@code lease} from now, for long-running handling. */
    public void extendLease(Duration lease) {
        Objects.requireNonNull(lease, "lease");
        if (settled.get()) {
            throw new IllegalStateException("Message " + id + " is already settled");
        }
        settlement.extendLease(lease);
    }

    private void settle() {
        if (!settled.compareAndSet(false, true)) {
            throw new IllegalStateException("Message " + id + " is already settled");
        }
    }

    @Override
    public String toString() {
        return "ReceivedMessage[id=" + id + ", size=" + body.length + ", attempt=" + deliveryAttempt + "]";
    }
}
