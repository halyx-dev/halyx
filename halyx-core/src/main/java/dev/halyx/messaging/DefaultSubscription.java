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
package dev.halyx.messaging;

import dev.halyx.capability.Capabilities;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.messaging.spi.DriverMessage;
import dev.halyx.messaging.spi.SubscriptionDriver;
import dev.halyx.storage.BlobKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** The portable subscription: claim-check resolution, settlement and interceptors over a driver. */
public final class DefaultSubscription implements Subscription {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultSubscription.class);

    /** Acks collected for one batch wait at most this long. */
    static final Duration ACK_LINGER = Duration.ofMillis(100);

    private final SubscriptionDriver driver;
    private final SubscriptionConfig config;
    private final InterceptorChain interceptors;
    private final AsyncSubscription async = new Async(InterceptorChain.blocking());
    private final List<DriverMessage> pendingAcks = new ArrayList<>();
    /** Listeners started on this subscription, stopped when its context closes; a running one is held by its poller. */
    private final Set<MessageConsumer> consumers = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
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
        MessageConsumer consumer = new PollingConsumer(this, Objects.requireNonNull(listener, "listener"),
                Objects.requireNonNull(options, "options"));
        consumers.add(consumer);
        return consumer;
    }

    /** Stops every listener started on this subscription, letting running handlers finish. */
    public void closeConsumers() {
        List<MessageConsumer> running;
        synchronized (consumers) {
            running = new ArrayList<>(consumers);
            consumers.clear();
        }
        running.forEach(MessageConsumer::close);
    }

    private ReceivedMessage toReceived(DriverMessage message) {
        String claim = message.attributes().get(ClaimCheck.ATTRIBUTE);
        if (claim != null && config.claimCheck().isPresent()) {
            byte[] body = execute("claimCheck", true, () -> config.claimCheck().get().store().getBytes(BlobKey.of(claim)));
            return received(message, body, withoutClaim(message), InterceptorChain.blocking());
        }
        warnIfUnclaimed(message, claim);
        return received(message, message.body(), message.attributes(), InterceptorChain.blocking());
    }

    private static Map<String, String> withoutClaim(DriverMessage message) {
        Map<String, String> attributes = new LinkedHashMap<>(message.attributes());
        attributes.remove(ClaimCheck.ATTRIBUTE);
        return attributes;
    }

    private void warnIfUnclaimed(DriverMessage message, String claim) {
        if (claim != null) {
            LOG.warn("Message {} on {} has a claim check ({}) but the subscription has no ClaimCheck configured; "
                    + "delivering the pointer as the body", message.id(), name(), claim);
        }
    }

    /** The message as delivered; its asynchronous settlements run any blocking work on {@code blocking}. */
    private ReceivedMessage received(DriverMessage message, byte[] body, Map<String, String> attributes, Executor blocking) {
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

            @Override
            public CompletableFuture<Void> ackAsync() {
                return executeAsync("ack", blocking, executor -> driver.ackAsync(message, executor));
            }

            @Override
            public CompletableFuture<Void> nackAsync(Duration delay) {
                return executeAsync("nack", blocking, executor -> driver.nackAsync(message, delay, executor));
            }

            @Override
            public CompletableFuture<Void> extendLeaseAsync(Duration lease) {
                return executeAsync("extendLease", blocking, executor -> driver.extendLeaseAsync(message, lease, executor));
            }
        });
    }

    /** The asynchronous {@link #execute}: the driver's future through the interceptors, its failure translated first. */
    private <T> CompletableFuture<T> executeAsync(String operation, Executor blocking,
                                                  Function<Executor, CompletableFuture<T>> call) {
        OperationContext context = new OperationContext(DefaultPublisher.SERVICE, operation, provider(), name(), true);
        return interceptors.executeAsync(context, () -> {
            CompletableFuture<T> future;
            try {
                future = call.apply(blocking);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(driver.translate(e));
            }
            return future.exceptionallyCompose(error -> {
                Throwable cause = AsyncInvocation.unwrap(error);
                return CompletableFuture.failedFuture(cause instanceof RuntimeException r ? driver.translate(r) : cause);
            });
        }, blocking);
    }

    @Override
    public AsyncSubscription async() {
        return async;
    }

    @Override
    public AsyncSubscription async(Executor blocking) {
        return blocking == InterceptorChain.blocking() ? async : new Async(Objects.requireNonNull(blocking, "blocking"));
    }

    /** The asynchronous view: receives on the driver's non-blocking path, claim checks through the blob store's. */
    private final class Async implements AsyncSubscription {

        private final Executor blocking;

        Async(Executor blocking) {
            this.blocking = blocking;
        }

        @Override
        public String provider() {
            return DefaultSubscription.this.provider();
        }

        @Override
        public String name() {
            return DefaultSubscription.this.name();
        }

        @Override
        public CompletableFuture<List<ReceivedMessage>> receive(int max, Duration wait) {
            if (max < 1) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("max must be at least 1"));
            }
            if (wait == null) {
                return CompletableFuture.failedFuture(new NullPointerException("wait"));
            }
            int batch = Math.min(max, Math.max(1, driver.maxReceiveBatch()));
            return executeAsync("receive", blocking, executor -> driver.receiveAsync(batch, wait, executor))
                    .thenCompose(raw -> {
                        List<CompletableFuture<ReceivedMessage>> messages = raw.stream().map(this::resolve).toList();
                        return CompletableFuture.allOf(messages.toArray(CompletableFuture[]::new))
                                .thenApply(done -> messages.stream().map(CompletableFuture::join).toList());
                    });
        }

        private CompletableFuture<ReceivedMessage> resolve(DriverMessage message) {
            String claim = message.attributes().get(ClaimCheck.ATTRIBUTE);
            if (claim != null && config.claimCheck().isPresent()) {
                return config.claimCheck().get().store().async(blocking).getBytes(BlobKey.of(claim))
                        .thenApply(body -> received(message, body, withoutClaim(message), blocking));
            }
            warnIfUnclaimed(message, claim);
            return CompletableFuture.completedFuture(received(message, message.body(), message.attributes(), blocking));
        }

        @Override
        public Subscription blocking() {
            return DefaultSubscription.this;
        }

        @Override
        public String toString() {
            return "Async" + DefaultSubscription.this;
        }
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
                            .name("halyx-ack-" + driver.name()).factory());
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
