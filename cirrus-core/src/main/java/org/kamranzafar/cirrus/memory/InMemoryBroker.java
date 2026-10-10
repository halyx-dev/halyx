package org.kamranzafar.cirrus.memory;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.messaging.MessagingCapability;
import org.kamranzafar.cirrus.messaging.OutgoingMessage;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.messaging.spi.DriverMessage;
import org.kamranzafar.cirrus.messaging.spi.MessagingErrors;
import org.kamranzafar.cirrus.messaging.spi.PublisherDriver;
import org.kamranzafar.cirrus.messaging.spi.SubscriptionDriver;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory topics and subscriptions with real lease semantics: received messages are invisible until
 * acked, nacked or their lease runs out; ordering keys deliver one message per key at a time; repeated
 * deduplication ids within five minutes are dropped. A subscription receives from its configured topic,
 * or from the topic of its own name (a queue). Lease: property {@value #LEASE} (default 30s).
 */
final class InMemoryBroker {

    static final String LEASE = "memory.lease";
    private static final Duration DEDUP_WINDOW = Duration.ofMinutes(5);
    private static final Capabilities CAPABILITIES = Capabilities.of(MessagingCapability.values());

    private final Map<String, List<Queue>> bindings = new ConcurrentHashMap<>();
    private final Map<String, Queue> queues = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Instant>> seenIds = new ConcurrentHashMap<>();

    PublisherDriver publisher(TopicConfig config) {
        return new Publisher(config.name());
    }

    SubscriptionDriver subscription(SubscriptionConfig config) {
        String topic = config.topic().orElse(config.name());
        Duration lease = config.property(LEASE).map(Duration::parse).orElse(Duration.ofSeconds(30));
        Queue queue = queues.computeIfAbsent(config.name(), name -> {
            Queue created = new Queue();
            bindings.computeIfAbsent(topic, t -> new CopyOnWriteArrayList<>()).add(created);
            return created;
        });
        return new Subscriber(config.name(), queue, lease);
    }

    void clear() {
        bindings.clear();
        queues.clear();
        seenIds.clear();
    }

    private static final class Entry {
        final String id = UUID.randomUUID().toString();
        final long sequence;
        final OutgoingMessage message;
        final Instant publishTime = Instant.now();
        Instant visibleAt;
        int attempts;
        String receipt;

        Entry(long sequence, OutgoingMessage message, Instant visibleAt) {
            this.sequence = sequence;
            this.message = message;
            this.visibleAt = visibleAt;
        }
    }

    private static final class Queue {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final List<Entry> entries = new ArrayList<>();
        private long sequence;

        void add(OutgoingMessage message, Instant visibleAt) {
            lock.lock();
            try {
                entries.add(new Entry(sequence++, message, visibleAt));
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        List<DriverMessage> receive(int max, Duration wait, Duration lease) {
            long deadline = System.nanoTime() + wait.toNanos();
            lock.lock();
            try {
                while (true) {
                    Instant now = Instant.now();
                    List<DriverMessage> out = new ArrayList<>();
                    Set<String> blockedKeys = new HashSet<>();
                    for (Entry e : entries) {
                        String key = e.message.orderingKey().orElse(null);
                        boolean visible = !e.visibleAt.isAfter(now);
                        if (key != null && (!visible || blockedKeys.contains(key))) {
                            blockedKeys.add(key); // later messages of this key wait for the earlier one
                            continue;
                        }
                        if (!visible || out.size() >= max) {
                            continue;
                        }
                        e.attempts++;
                        e.receipt = UUID.randomUUID().toString();
                        e.visibleAt = now.plus(lease);
                        if (key != null) {
                            blockedKeys.add(key);
                        }
                        out.add(new DriverMessage(e.id, e.receipt, e.message.body(), e.message.attributes(),
                                key, e.publishTime, e.attempts));
                    }
                    long remaining = deadline - System.nanoTime();
                    if (!out.isEmpty() || remaining <= 0) {
                        return out;
                    }
                    long untilNextVisible = entries.stream()
                            .map(e -> Duration.between(now, e.visibleAt).toNanos())
                            .filter(n -> n > 0).min(Long::compare).orElse(remaining);
                    changed.awaitNanos(Math.max(1_000_000, Math.min(remaining, untilNextVisible)));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                lock.unlock();
            }
        }

        void update(String receipt, Optional<Instant> visibleAt) {
            lock.lock();
            try {
                Iterator<Entry> it = entries.iterator();
                while (it.hasNext()) {
                    Entry e = it.next();
                    if (receipt.equals(e.receipt)) {
                        if (visibleAt.isEmpty()) {
                            it.remove();
                        } else {
                            e.visibleAt = visibleAt.get();
                        }
                        changed.signalAll();
                        return;
                    }
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private final class Publisher implements PublisherDriver {
        private final String topic;

        Publisher(String topic) {
            this.topic = topic;
        }

        @Override
        public String provider() {
            return InMemoryProvider.ID;
        }

        @Override
        public String topic() {
            return topic;
        }

        @Override
        public Capabilities capabilities() {
            return CAPABILITIES;
        }

        @Override
        public List<String> publish(List<OutgoingMessage> messages) {
            List<String> ids = new ArrayList<>(messages.size());
            Instant now = Instant.now();
            var seen = seenIds.computeIfAbsent(topic, t -> new ConcurrentHashMap<>());
            seen.values().removeIf(at -> at.isBefore(now.minus(DEDUP_WINDOW)));
            for (OutgoingMessage message : messages) {
                ids.add(UUID.randomUUID().toString());
                if (message.deduplicationId().isPresent() && seen.putIfAbsent(message.deduplicationId().get(), now) != null) {
                    continue;
                }
                Instant visibleAt = message.delay().map(now::plus).orElse(now);
                bindings.getOrDefault(topic, List.of()).forEach(q -> q.add(message, visibleAt));
            }
            return ids;
        }

        @Override
        public int maxBatchSize() {
            return 100;
        }

        @Override
        public long maxMessageBytes() {
            return 256 * 1024;
        }

        @Override
        public CloudException translate(Throwable error) {
            return MessagingErrors.translate(error, InMemoryProvider.ID, e -> Optional.empty());
        }

        @Override
        public <T> T unwrap(Class<T> nativeType) {
            return MessagingErrors.unwrap(nativeType, InMemoryProvider.ID, List.of());
        }
    }

    private static final class Subscriber implements SubscriptionDriver {
        private final String name;
        private final Queue queue;
        private final Duration lease;

        Subscriber(String name, Queue queue, Duration lease) {
            this.name = name;
            this.queue = queue;
            this.lease = lease;
        }

        @Override
        public String provider() {
            return InMemoryProvider.ID;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public Capabilities capabilities() {
            return CAPABILITIES;
        }

        @Override
        public List<DriverMessage> receive(int max, Duration wait) {
            return queue.receive(max, wait, lease);
        }

        @Override
        public int maxReceiveBatch() {
            return 100;
        }

        @Override
        public void ack(DriverMessage message) {
            queue.update(message.receipt(), Optional.empty());
        }

        @Override
        public void nack(DriverMessage message, Duration delay) {
            queue.update(message.receipt(), Optional.of(Instant.now().plus(delay)));
        }

        @Override
        public void extendLease(DriverMessage message, Duration lease) {
            queue.update(message.receipt(), Optional.of(Instant.now().plus(lease)));
        }

        @Override
        public Duration defaultLease() {
            return lease;
        }

        @Override
        public CloudException translate(Throwable error) {
            return MessagingErrors.translate(error, InMemoryProvider.ID, e -> Optional.empty());
        }

        @Override
        public <T> T unwrap(Class<T> nativeType) {
            return MessagingErrors.unwrap(nativeType, InMemoryProvider.ID, List.of());
        }
    }
}
