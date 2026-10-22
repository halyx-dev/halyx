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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pulls on one virtual thread and handles each message on its own virtual thread, at most
 * {@code concurrency} at once: the poller only asks for as many messages as there are free slots, so a
 * slow handler slows pulling instead of piling up leased messages. From receipt until it is settled, a
 * message's lease is extended every half lease. Messages with the same ordering key are handled one after
 * another in delivery order, also across receive batches. Success acks; an exception nacks with
 * exponential backoff.
 */
final class PollingConsumer implements MessageConsumer {

    private static final Logger LOG = LoggerFactory.getLogger(PollingConsumer.class);
    private static final Duration MAX_RECEIVE_BACKOFF = Duration.ofSeconds(30);

    private final Subscription subscription;
    private final MessageListener listener;
    private final ConsumerOptions options;
    private final Semaphore slots;
    private final ScheduledExecutorService leases;
    private final Thread poller;
    private final ExecutorService handlers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("halyx-handle-", 0).factory());
    /** The last scheduled handler per ordering key; the next message of that key runs after it. */
    /** The consumer whose handler runs on this thread, so close() from inside a handler doesn't wait for itself. */
    private static final ThreadLocal<PollingConsumer> HANDLING = new ThreadLocal<>();

    private final ConcurrentMap<String, CompletableFuture<Void>> keyTails = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong acked = new AtomicLong();
    private final AtomicLong nacked = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong receiveErrors = new AtomicLong();

    PollingConsumer(Subscription subscription, MessageListener listener, ConsumerOptions options) {
        this.subscription = subscription;
        this.listener = listener;
        this.options = options;
        this.slots = new Semaphore(options.concurrency());
        this.leases = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon()
                .name("halyx-lease-" + subscription.name()).factory());
        this.poller = Thread.ofVirtual().name("halyx-poll-" + subscription.name()).start(this::poll);
    }

    private void poll() {
        int consecutiveErrors = 0;
        while (running) {
            slots.acquireUninterruptibly();
            int reserved = 1 + slots.drainPermits();
            int wanted = Math.min(reserved, options.batchSize());
            slots.release(reserved - wanted);
            if (!running) {
                slots.release(wanted);
                break;
            }
            List<ReceivedMessage> messages;
            try {
                messages = subscription.receive(wanted, options.pollWait());
                consecutiveErrors = 0;
            } catch (RuntimeException e) {
                slots.release(wanted);
                if (!running) {
                    break; // close() interrupted the long poll
                }
                receiveErrors.incrementAndGet();
                consecutiveErrors++;
                long backoff = Math.min(MAX_RECEIVE_BACKOFF.toMillis(), 200L << Math.min(consecutiveErrors, 8));
                LOG.warn("Receive from {} failed ({} in a row); retrying in {} ms", subscription.name(),
                        consecutiveErrors, backoff, e);
                sleep(ThreadLocalRandom.current().nextLong(backoff / 2, backoff + 1));
                continue;
            }
            slots.release(wanted - messages.size());
            received.addAndGet(messages.size());
            for (ReceivedMessage message : messages) {
                if (!running) {
                    settleQuietly(message, false, Duration.ZERO);
                    slots.release();
                    continue;
                }
                dispatch(message);
            }
        }
    }

    private void dispatch(ReceivedMessage message) {
        ScheduledFuture<?> extender = startLeaseExtension(message);
        String key = message.orderingKey().orElse(null);
        if (key == null) {
            handlers.execute(() -> handle(message, extender));
            return;
        }
        chain(keyTails, key, () -> handle(message, extender), handlers);
    }

    /**
     * Runs {@code task} on {@code executor} after every task chained before it on {@code key}, and forgets the
     * key once its last task is done.
     */
    static void chain(ConcurrentMap<String, CompletableFuture<Void>> tails, String key, Runnable task,
                      Executor executor) {
        CompletableFuture<Void> next = tails.compute(key, (k, tail) -> {
            CompletableFuture<Void> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
            return previous.handle((v, e) -> null).thenRunAsync(task, executor);
        });
        // Registered once compute has returned: a task already done would otherwise remove its key from inside
        // compute, which ConcurrentHashMap forbids.
        next.whenComplete((v, e) -> tails.remove(key, next));
    }

    private void handle(ReceivedMessage message, ScheduledFuture<?> extender) {
        HANDLING.set(this);
        try {
            listener.onMessage(message);
            if (!message.isSettled()) {
                settleQuietly(message, true, Duration.ZERO);
            }
        } catch (Throwable e) {
            failed.incrementAndGet();
            LOG.warn("Handler failed for {} on {} (attempt {})", message.id(), subscription.name(),
                    message.deliveryAttempt(), e);
            if (!message.isSettled()) {
                settleQuietly(message, false, options.backoff(message.deliveryAttempt()));
            }
            if (e instanceof Error error && !(e instanceof AssertionError)) {
                throw error;
            }
        } finally {
            HANDLING.remove();
            if (extender != null) {
                extender.cancel(false);
            }
            slots.release();
        }
    }

    private ScheduledFuture<?> startLeaseExtension(ReceivedMessage message) {
        Duration lease = subscription.defaultLease();
        if (!options.extendLease() || lease.isZero() || lease.isNegative()) {
            return null;
        }
        long period = Math.max(lease.toMillis() / 2, 100);
        return leases.scheduleAtFixedRate(() -> {
            try {
                if (!message.isSettled()) {
                    message.extendLease(lease);
                }
            } catch (RuntimeException e) {
                LOG.debug("Could not extend the lease of {} on {}", message.id(), subscription.name(), e);
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    private void settleQuietly(ReceivedMessage message, boolean ack, Duration delay) {
        try {
            if (ack) {
                message.ackDeferred();
                acked.incrementAndGet();
            } else {
                message.nack(delay);
                nacked.incrementAndGet();
            }
        } catch (RuntimeException e) {
            // The lease runs out and the message is redelivered anyway.
            LOG.warn("Could not {} {} on {}", ack ? "ack" : "nack", message.id(), subscription.name(), e);
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public ConsumerStatistics statistics() {
        return new ConsumerStatistics(received.get(), acked.get(), nacked.get(), failed.get(),
                options.concurrency() - slots.availablePermits(), receiveErrors.get());
    }

    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        poller.interrupt();
        if (HANDLING.get() == this) {
            // Waiting for every handler would wait for the caller itself: stopping finishes once it returns.
            Thread.ofVirtual().name("halyx-stop-" + subscription.name()).start(this::finishClosing);
            return;
        }
        finishClosing();
    }

    /** Waits for running handlers and the poller, then stops lease extension and ack batching. */
    private void finishClosing() {
        try {
            // All slots free means no handler is running and the poller holds none.
            if (!slots.tryAcquire(options.concurrency(), options.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("Handlers on {} still running after {}", subscription.name(), options.shutdownTimeout());
            } else {
                slots.release(options.concurrency());
            }
            poller.join(options.pollWait().plusSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            leases.shutdownNow();
            handlers.shutdown();
            if (subscription instanceof DefaultSubscription portable) {
                portable.stopAckBatching();
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // close() interrupts the poller to stop it; the loop checks running.
        }
    }

    @Override
    public String toString() {
        return "MessageConsumer[" + subscription + ", " + statistics() + "]";
    }
}
