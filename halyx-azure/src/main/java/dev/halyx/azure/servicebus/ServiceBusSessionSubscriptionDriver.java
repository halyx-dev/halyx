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
package dev.halyx.azure.servicebus;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSessionReceiverClient;
import dev.halyx.azure.AzureProvider;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.messaging.MessagingCapability;
import dev.halyx.messaging.spi.AbstractSubscriptionDriver;
import dev.halyx.messaging.spi.DriverMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Receives from a session-enabled Service Bus queue or subscription. It holds up to {@code maxSessions}
 * sessions at a time; while it holds one, no other consumer can, so each session's messages (one ordering
 * key) are delivered in order, one consumer at a time. A session is released once it has no messages left
 * and nothing in flight. Lease extension renews the session lock, which covers its messages.
 */
public final class ServiceBusSessionSubscriptionDriver extends AbstractSubscriptionDriver {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusSessionSubscriptionDriver.class);
    private static final Duration SESSION_POLL = Duration.ofMillis(500);

    /** One accepted session and how many of its messages are being handled. */
    private static final class Session {
        final ServiceBusReceiverClient receiver;
        final AtomicInteger inFlight = new AtomicInteger();

        Session(ServiceBusReceiverClient receiver) {
            this.receiver = receiver;
        }
    }

    private record Locked(ServiceBusReceivedMessage message, Session session) {
    }

    private final ServiceBusSessionReceiverClient sessions;
    private final String name;
    private final int maxSessions;
    private final List<Session> active = new CopyOnWriteArrayList<>();
    private final Map<String, Locked> locked = new ConcurrentHashMap<>();
    private CompletableFuture<ServiceBusReceiverClient> accepting;
    /**
     * One receive at a time, which may wait up to its full poll; a lock, not a monitor, which would pin a virtual
     * thread's carrier on Java 21 for that long.
     */
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Duration lease = Duration.ofSeconds(30);

    public ServiceBusSessionSubscriptionDriver(ServiceBusSessionReceiverClient sessions, String name, int maxSessions) {
        super(AzureProvider.ID);
        this.sessions = sessions;
        this.name = name;
        this.maxSessions = Math.max(1, maxSessions);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(MessagingCapability.ORDERING);
    }

    @Override
    public List<DriverMessage> receive(int max, Duration wait) {
        lock.lock();
        try {
            long deadline = System.nanoTime() + wait.toNanos();
            List<DriverMessage> out = new ArrayList<>();
            while (true) {
                adoptAcceptedSession(active.isEmpty() ? remaining(deadline) : Duration.ZERO);
                for (Session session : active) {
                    if (out.size() >= max) {
                        break;
                    }
                    List<ServiceBusReceivedMessage> batch = new ArrayList<>();
                    session.receiver.receiveMessages(max - out.size(), SESSION_POLL).forEach(batch::add);
                    if (batch.isEmpty() && session.inFlight.get() == 0) {
                        release(session); // drained: let another session in
                        continue;
                    }
                    for (ServiceBusReceivedMessage message : batch) {
                        out.add(toDriverMessage(message, session));
                    }
                }
                if (!out.isEmpty() || System.nanoTime() >= deadline) {
                    return out;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
    }

    /** Takes over a session accepted in the background, and keeps one accept running while there is room. */
    private void adoptAcceptedSession(Duration wait) {
        if (accepting == null && active.size() < maxSessions) {
            accepting = CompletableFuture.supplyAsync(sessions::acceptNextSession,
                    r -> Thread.ofVirtual().name("halyx-servicebus-accept").start(r));
        }
        if (accepting == null) {
            return;
        }
        try {
            ServiceBusReceiverClient receiver = wait.isZero() && !accepting.isDone() ? null
                    : accepting.get(Math.max(1, wait.toMillis()), TimeUnit.MILLISECONDS);
            if (receiver != null) {
                active.add(new Session(receiver));
                accepting = null;
            }
        } catch (TimeoutException stillWaiting) {
            // keep waiting in the background
        } catch (ExecutionException e) {
            accepting = null; // no session became available in time: try again next round
            LOG.debug("No Service Bus session accepted on {}", name, e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void release(Session session) {
        active.remove(session);
        try {
            session.receiver.close();
        } catch (RuntimeException e) {
            LOG.debug("Closing a Service Bus session on {} failed", name, e);
        }
    }

    private DriverMessage toDriverMessage(ServiceBusReceivedMessage message, Session session) {
        String receipt = UUID.randomUUID().toString();
        locked.put(receipt, new Locked(message, session));
        session.inFlight.incrementAndGet();
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
        return new DriverMessage(message.getMessageId(), receipt, message.getBody().toBytes(), attributes,
                message.getSessionId(),
                message.getEnqueuedTime() == null ? null : message.getEnqueuedTime().toInstant(),
                (int) message.getDeliveryCount() + 1);
    }

    @Override
    public int maxReceiveBatch() {
        return 100;
    }

    @Override
    public void ack(DriverMessage message) {
        Locked entry = locked.remove(message.receipt());
        if (entry != null) {
            try {
                entry.session().receiver.complete(entry.message());
            } finally {
                entry.session().inFlight.decrementAndGet();
            }
        }
    }

    @Override
    public void nack(DriverMessage message, Duration delay) {
        Locked entry = locked.remove(message.receipt());
        if (entry != null) {
            try {
                entry.session().receiver.abandon(entry.message());
            } finally {
                entry.session().inFlight.decrementAndGet();
            }
        }
    }

    /** Session messages share the session's lock: renewing it keeps all of them. */
    @Override
    public void extendLease(DriverMessage message, Duration extension) {
        Locked entry = locked.get(message.receipt());
        if (entry != null) {
            entry.session().receiver.renewSessionLock();
        }
    }

    @Override
    public Duration defaultLease() {
        return lease;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return ServiceBusErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(sessions);
    }

    @Override
    public void close() {
        lock.lock();
        try {
            active.forEach(this::release);
            if (accepting != null) {
                accepting.thenAccept(ServiceBusReceiverClient::close);
            }
            sessions.close();
        } finally {
            lock.unlock();
        }
    }
}
