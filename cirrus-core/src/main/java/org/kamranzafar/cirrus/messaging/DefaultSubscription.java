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

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.interceptor.OperationContext;
import org.kamranzafar.cirrus.messaging.spi.DriverMessage;
import org.kamranzafar.cirrus.messaging.spi.SubscriptionDriver;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** The portable subscription: claim-check resolution, settlement and interceptors over a driver. */
public final class DefaultSubscription implements Subscription {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultSubscription.class);

    /** Acks collected for one batch wait at most this long. */
    static final Duration ACK_LINGER = Duration.ofMillis(100);

    private final SubscriptionDriver driver;
    private final SubscriptionConfig config;
    private final InterceptorChain interceptors;
    private final List<DriverMessage> pendingAcks = new ArrayList<>();
    private ScheduledExecutorService ackFlusher;

    public DefaultSubscription(SubscriptionDriver driver, SubscriptionConfig config, InterceptorChain interceptors) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.config = Objects.requireNonNull(config, "config");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
    }

    @Override
    public String provider() {
        return driver.provider();
    }

    @Override
    public String name() {
        return driver.name();
    }

    @Override
    public Capabilities capabilities() {
        return driver.capabilities();
    }

    @Override
    public Duration defaultLease() {
        return driver.defaultLease();
    }

    @Override
    public List<ReceivedMessage> receive(int max, Duration wait) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be at least 1");
        }
        Objects.requireNonNull(wait, "wait");
        int batch = Math.min(max, Math.max(1, driver.maxReceiveBatch()));
        // Receiving is safe to retry: anything delivered to a failed call reappears after its lease.
        List<DriverMessage> raw = execute("receive", true, () -> driver.receive(batch, wait));
        List<ReceivedMessage> messages = new ArrayList<>(raw.size());
        for (DriverMessage message : raw) {
            messages.add(toReceived(message));
        }
        return messages;
    }

    @Override
    public MessageConsumer listen(MessageListener listener, ConsumerOptions options) {
        return new PollingConsumer(this, Objects.requireNonNull(listener, "listener"),
                Objects.requireNonNull(options, "options"));
    }

    private ReceivedMessage toReceived(DriverMessage message) {
        byte[] body = message.body();
        Map<String, String> attributes = message.attributes();
        String claim = attributes.get(ClaimCheck.ATTRIBUTE);
        if (claim != null && config.claimCheck().isPresent()) {
            body = execute("claimCheck", true, () -> config.claimCheck().get().store().getBytes(BlobKey.of(claim)));
            attributes = new LinkedHashMap<>(attributes);
            attributes.remove(ClaimCheck.ATTRIBUTE);
        } else if (claim != null) {
            LOG.warn("Message {} on {} has a claim check ({}) but the subscription has no ClaimCheck configured; "
                    + "delivering the pointer as the body", message.id(), name(), claim);
        }
        return new ReceivedMessage(message.id(), body, attributes, message.orderingKey(), message.publishTime(),
                message.deliveryAttempt(), new ReceivedMessage.Settlement() {
            @Override
            public void ack() {
                execute("ack", true, () -> {
                    driver.ack(message);
                    return null;
                });
            }

            @Override
            public void ackDeferred() {
                deferAck(message);
            }

            @Override
            public void nack(Duration delay) {
                execute("nack", true, () -> {
                    driver.nack(message, delay);
                    return null;
                });
            }

            @Override
            public void extendLease(Duration lease) {
                execute("extendLease", true, () -> {
                    driver.extendLease(message, lease);
                    return null;
                });
            }
        });
    }

    private void deferAck(DriverMessage message) {
        int batch = driver.maxAckBatch();
        if (batch <= 1) {
            execute("ack", true, () -> {
                driver.ack(message);
                return null;
            });
            return;
        }
        List<DriverMessage> full = null;
        synchronized (pendingAcks) {
            pendingAcks.add(message);
            if (pendingAcks.size() >= batch) {
                full = new ArrayList<>(pendingAcks);
                pendingAcks.clear();
            } else if (pendingAcks.size() == 1) {
                if (ackFlusher == null) {
                    ackFlusher = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
                            .name("cirrus-ack-" + driver.name()).factory());
                }
                ackFlusher.schedule(this::flushAcks, ACK_LINGER.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
        if (full != null) {
            sendAcks(full);
        }
    }

    /** Sends any acks waiting for a batch. */
    void flushAcks() {
        List<DriverMessage> batch;
        synchronized (pendingAcks) {
            if (pendingAcks.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pendingAcks);
            pendingAcks.clear();
        }
        sendAcks(batch);
    }

    private void sendAcks(List<DriverMessage> batch) {
        try {
            execute("ack", true, () -> {
                driver.ackAll(batch);
                return null;
            });
        } catch (RuntimeException e) {
            LOG.warn("Batched ack of {} message(s) on {} failed; they will be redelivered", batch.size(), name(), e);
        }
    }

    /** Flushes pending acks and stops the flusher (when a consumer stops). */
    void stopAckBatching() {
        flushAcks();
        synchronized (pendingAcks) {
            if (ackFlusher != null) {
                ackFlusher.shutdown();
                ackFlusher = null;
            }
        }
    }

    private <T> T execute(String operation, boolean idempotent, Supplier<T> call) {
        OperationContext context = new OperationContext(DefaultPublisher.SERVICE, operation, provider(), name(), idempotent);
        return interceptors.execute(context, () -> {
            try {
                return call.get();
            } catch (RuntimeException e) {
                throw driver.translate(e);
            }
        });
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "Subscription[" + provider() + ":" + name() + "]";
    }
}
