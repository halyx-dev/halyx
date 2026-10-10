package org.kamranzafar.cirrus.azure.servicebus;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.kamranzafar.cirrus.azure.AzureProvider;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.messaging.spi.DriverMessage;
import org.kamranzafar.cirrus.messaging.spi.MessagingErrors;
import org.kamranzafar.cirrus.messaging.spi.SubscriptionDriver;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Receives from a Service Bus queue or topic subscription in peek-lock mode: ack completes, nack abandons
 * (redelivered at once: Service Bus has no delayed abandon) and lease extension renews the lock. Service
 * Bus renews by the entity's lock duration only, so a longer extension keeps renewing in the background
 * until it has elapsed or the message is settled. The lease is measured from the locks the service grants. Repeatedly failing messages move to the entity's dead-letter queue after its max
 * delivery count.
 */
public final class ServiceBusSubscriptionDriver implements SubscriptionDriver {

    /** Until a lock is seen; Service Bus' default lock duration is 60 seconds, and the minimum 5. */
    private static final Duration INITIAL_LEASE = Duration.ofSeconds(30);

    private final ServiceBusReceiverClient receiver;
    private final String name;
    /** Settlement needs the received message itself, not only its lock token. */
    private final Map<String, ServiceBusReceivedMessage> locked = new ConcurrentHashMap<>();
    private volatile Duration lease = INITIAL_LEASE;
    /** Background renewals for extensions longer than one lock, by receipt. */
    private final Map<String, ScheduledFuture<?>> renewals = new ConcurrentHashMap<>();
    private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("cirrus-servicebus-renew").factory());

    public ServiceBusSubscriptionDriver(ServiceBusReceiverClient receiver, String name) {
        this.receiver = receiver;
        this.name = name;
    }

    @Override
    public String provider() {
        return AzureProvider.ID;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.none();
    }

    @Override
    public List<DriverMessage> receive(int max, Duration wait) {
        purgeExpiredLocks();
        List<DriverMessage> out = new ArrayList<>();
        for (ServiceBusReceivedMessage message : receiver.receiveMessages(max, wait.isZero() ? Duration.ofMillis(100) : wait)) {
            String receipt = UUID.randomUUID().toString();
            locked.put(receipt, message);
            OffsetDateTime until = message.getLockedUntil();
            if (until != null) {
                Duration granted = Duration.between(Instant.now(), until.toInstant());
                if (granted.compareTo(Duration.ofSeconds(1)) > 0) {
                    lease = granted.plusSeconds(1).withNanos(0);
                }
            }
            Map<String, String> attributes = new LinkedHashMap<>();
            message.getApplicationProperties().forEach((k, v) -> {
                if (v != null) {
                    attributes.put(k, v.toString());
                }
            });
            out.add(new DriverMessage(message.getMessageId(), receipt, message.getBody().toBytes(), attributes,
                    message.getSessionId(),
                    message.getEnqueuedTime() == null ? null : message.getEnqueuedTime().toInstant(),
                    (int) message.getDeliveryCount() + 1));
        }
        return out;
    }

    private void purgeExpiredLocks() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(5);
        locked.values().removeIf(m -> m.getLockedUntil() != null && m.getLockedUntil().isBefore(cutoff));
    }

    @Override
    public int maxReceiveBatch() {
        return 100;
    }

    @Override
    public void ack(DriverMessage message) {
        stopRenewing(message);
        ServiceBusReceivedMessage native_ = locked.remove(message.receipt());
        if (native_ != null) {
            receiver.complete(native_);
        }
    }

    @Override
    public void nack(DriverMessage message, Duration delay) {
        stopRenewing(message);
        ServiceBusReceivedMessage native_ = locked.remove(message.receipt());
        if (native_ != null) {
            receiver.abandon(native_);
        }
    }

    @Override
    public void extendLease(DriverMessage message, Duration extension) {
        ServiceBusReceivedMessage native_ = locked.get(message.receipt());
        if (native_ == null) {
            return;
        }
        stopRenewing(message);
        receiver.renewMessageLock(native_);
        Instant until = Instant.now().plus(extension);
        long period = Math.max(500, lease.toMillis() / 2);
        if (extension.compareTo(lease) > 0) {
            renewals.put(message.receipt(), renewer.scheduleAtFixedRate(() -> {
                if (Instant.now().plus(lease).isAfter(until) || !locked.containsKey(message.receipt())) {
                    stopRenewing(message);
                    return;
                }
                try {
                    receiver.renewMessageLock(native_);
                } catch (RuntimeException e) {
                    stopRenewing(message); // lock lost: the message is redelivered
                }
            }, period, period, TimeUnit.MILLISECONDS));
        }
    }

    private void stopRenewing(DriverMessage message) {
        ScheduledFuture<?> renewal = renewals.remove(message.receipt());
        if (renewal != null) {
            renewal.cancel(false);
        }
    }

    @Override
    public Duration defaultLease() {
        return lease;
    }

    @Override
    public CloudException translate(Throwable error) {
        return MessagingErrors.translate(error, AzureProvider.ID, ServiceBusErrors::translate);
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return MessagingErrors.unwrap(nativeType, AzureProvider.ID, List.of(receiver));
    }

    @Override
    public void close() {
        renewer.shutdownNow();
        receiver.close();
    }
}
