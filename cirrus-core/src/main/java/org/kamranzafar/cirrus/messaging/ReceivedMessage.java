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
