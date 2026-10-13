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
package dev.halyx.memory;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.SendResult;
import dev.halyx.stream.StartPosition;
import dev.halyx.stream.StreamCapability;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.stream.spi.AbstractConsumerDriver;
import dev.halyx.stream.spi.AbstractProducerDriver;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.DriverRecord;
import dev.halyx.stream.spi.Partition;
import dev.halyx.stream.spi.ProducerDriver;
import dev.halyx.stream.spi.RebalanceListener;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory partitioned topics with consumer groups: records with the same key go to the same partition, each
 * group keeps its own committed offsets, and members share a topic's partitions, rebalancing when one joins
 * or leaves. Topics are created on first use with {@value #PARTITIONS} partitions (default 3). {@code sendAll}
 * is atomic, so transactions hold trivially.
 */
final class InMemoryStreams {

    static final String PARTITIONS = "memory.partitions";
    private static final int DEFAULT_PARTITIONS = 3;
    private static final int MAX_POLL_RECORDS = 500;
    /** Kafka's default {@code max.request.size}, so size checks behave as on a real cluster. */
    private static final long MAX_RECORD_BYTES = 1024 * 1024;
    private static final Capabilities CAPABILITIES = Capabilities.of(StreamCapability.values());

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final Map<String, Topic> topics = new ConcurrentHashMap<>();
    private final Map<String, Group> groups = new HashMap<>();

    private record Stored(String key, byte[] value, Map<String, String> headers, Instant timestamp) {
    }

    private static final class Topic {
        final String name;
        final List<List<Stored>> partitions = new ArrayList<>();
        final AtomicInteger next = new AtomicInteger();

        Topic(String name, int partitionCount) {
            this.name = name;
            for (int i = 0; i < partitionCount; i++) {
                partitions.add(new ArrayList<>());
            }
        }
    }

    private static final class Group {
        final Map<Integer, Long> committed = new HashMap<>();
        final List<Member> members = new ArrayList<>();
        int generation;
    }

    private Topic topic(String name, Map<String, String> properties) {
        int count = Integer.parseInt(properties.getOrDefault(PARTITIONS, Integer.toString(DEFAULT_PARTITIONS)));
        return topics.computeIfAbsent(name, n -> new Topic(n, count));
    }

    ProducerDriver producer(StreamProducerConfig config) {
        return new Producer(topic(config.topic(), config.cluster().properties()));
    }

    ConsumerDriver consumer(StreamConsumerConfig config) {
        Topic topic = topic(config.topic(), config.cluster().properties());
        lock.lock();
        try {
            return new Member(topic, groups.computeIfAbsent(config.topic() + "|" + config.group(), g -> new Group()), config);
        } finally {
            lock.unlock();
        }
    }

    void clear() {
        lock.lock();
        try {
            topics.clear();
            groups.clear();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private final class Producer extends AbstractProducerDriver {
        private final Topic topic;

        Producer(Topic topic) {
            super(InMemoryProvider.ID);
            this.topic = topic;
        }

        @Override
        public String topic() {
            return topic.name;
        }

        @Override
        public Capabilities capabilities() {
            return CAPABILITIES;
        }

        @Override
        public List<SendResult> send(List<OutgoingRecord> records) {
            lock.lock();
            try {
                List<SendResult> results = new ArrayList<>(records.size());
                Instant now = Instant.now();
                int count = topic.partitions.size();
                for (OutgoingRecord record : records) {
                    int partition = record.key().map(k -> Math.floorMod(k.hashCode(), count))
                            .orElseGet(() -> Math.floorMod(topic.next.getAndIncrement(), count));
                    List<Stored> log = topic.partitions.get(partition);
                    log.add(new Stored(record.key().orElse(null), record.value(), record.headers(), now));
                    results.add(new SendResult(topic.name, partition, log.size() - 1, now));
                }
                changed.signalAll();
                return results;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int partitionCount() {
            return topic.partitions.size();
        }

        @Override
        public long maxRecordBytes() {
            return MAX_RECORD_BYTES;
        }

        @Override
        protected Optional<CloudException> translateNative(Throwable error) {
            return Optional.empty();
        }

        @Override
        protected List<Object> nativeClients() {
            return List.of();
        }
    }

    private final class Member extends AbstractConsumerDriver {
        private final Topic topic;
        private final Group group;
        private final StreamConsumerConfig config;
        private final Set<Integer> owned = new HashSet<>();
        private final Set<Integer> paused = new HashSet<>();
        private final Map<Integer, Long> positions = new HashMap<>();
        private RebalanceListener listener;
        private int seenGeneration = -1;
        private boolean joined;
        private boolean woken;

        Member(Topic topic, Group group, StreamConsumerConfig config) {
            super(InMemoryProvider.ID);
            this.topic = topic;
            this.group = group;
            this.config = config;
        }

        @Override
        public String topic() {
            return topic.name;
        }

        @Override
        public String group() {
            return config.group();
        }

        @Override
        public Capabilities capabilities() {
            return CAPABILITIES;
        }

        @Override
        public void subscribe(RebalanceListener listener) {
            lock.lock();
            try {
                this.listener = listener;
                if (!joined) {
                    joined = true;
                    group.members.add(this);
                    group.generation++;
                    changed.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }

        @Override
        public List<DriverRecord> poll(Duration wait) {
            long deadline = System.nanoTime() + wait.toNanos();
            while (true) {
                rebalanceIfNeeded();
                lock.lock();
                try {
                    if (seenGeneration != group.generation) {
                        continue; // another member joined or left meanwhile
                    }
                    List<DriverRecord> records = read();
                    if (!records.isEmpty()) {
                        return records;
                    }
                    if (woken) {
                        woken = false;
                        return List.of();
                    }
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return List.of();
                    }
                    changed.await(left, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return List.of();
                } finally {
                    lock.unlock();
                }
            }
        }

        /** Works out the new assignment under the lock, then tells the listener outside it, as Kafka does in poll. */
        private void rebalanceIfNeeded() {
            List<Partition> revoked = new ArrayList<>();
            List<Partition> assigned = new ArrayList<>();
            lock.lock();
            try {
                if (!joined) {
                    throw new IllegalStateException("subscribe before polling");
                }
                if (seenGeneration == group.generation) {
                    return;
                }
                seenGeneration = group.generation;
                int index = group.members.indexOf(this);
                int members = group.members.size();
                Set<Integer> now = new HashSet<>();
                for (int p = 0; p < topic.partitions.size(); p++) {
                    if (index >= 0 && p % members == index) {
                        now.add(p);
                    }
                }
                for (int p : owned) {
                    if (!now.contains(p)) {
                        revoked.add(new Partition(topic.name, p));
                    }
                }
                for (int p : now) {
                    if (!owned.contains(p)) {
                        assigned.add(new Partition(topic.name, p));
                    }
                }
            } finally {
                lock.unlock();
            }
            if (!revoked.isEmpty()) {
                listener.onRevoked(revoked);
            }
            lock.lock();
            try {
                for (Partition p : revoked) {
                    owned.remove(p.partition());
                    paused.remove(p.partition());
                    positions.remove(p.partition());
                }
                for (Partition p : assigned) {
                    owned.add(p.partition());
                    Long committed = group.committed.get(p.partition());
                    positions.put(p.partition(), committed != null ? committed
                            : config.startPosition() == StartPosition.EARLIEST ? 0L : topic.partitions.get(p.partition()).size());
                }
            } finally {
                lock.unlock();
            }
            if (!assigned.isEmpty()) {
                listener.onAssigned(assigned);
            }
        }

        private List<DriverRecord> read() {
            List<DriverRecord> records = new ArrayList<>();
            for (int p : owned) {
                if (paused.contains(p)) {
                    continue;
                }
                List<Stored> log = topic.partitions.get(p);
                long position = positions.get(p);
                while (position < log.size() && records.size() < MAX_POLL_RECORDS) {
                    Stored s = log.get((int) position);
                    records.add(new DriverRecord(new Partition(topic.name, p), position, s.key(), s.value().clone(),
                            s.headers(), s.timestamp()));
                    position++;
                }
                positions.put(p, position);
            }
            return records;
        }

        @Override
        public void pause(Collection<Partition> partitions) {
            lock.lock();
            try {
                partitions.forEach(p -> paused.add(p.partition()));
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void resume(Collection<Partition> partitions) {
            lock.lock();
            try {
                partitions.forEach(p -> paused.remove(p.partition()));
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void commit(Map<Partition, Long> nextOffsets) {
            lock.lock();
            try {
                nextOffsets.forEach((p, next) -> {
                    if (owned.contains(p.partition())) {
                        group.committed.merge(p.partition(), next, Math::max);
                    }
                });
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void commitAsync(Map<Partition, Long> nextOffsets) {
            commit(nextOffsets);
        }

        @Override
        public void wakeup() {
            lock.lock();
            try {
                woken = true;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Leaves the group, telling the listener its partitions are revoked first, as Kafka does. */
        @Override
        public void close() {
            List<Partition> revoked;
            lock.lock();
            try {
                revoked = owned.stream().map(p -> new Partition(topic.name, p)).toList();
            } finally {
                lock.unlock();
            }
            if (listener != null && !revoked.isEmpty()) {
                listener.onRevoked(revoked);
            }
            lock.lock();
            try {
                owned.clear();
                if (joined && group.members.remove(this)) {
                    group.generation++;
                    changed.signalAll();
                }
                joined = false;
            } finally {
                lock.unlock();
            }
        }

        @Override
        protected Optional<CloudException> translateNative(Throwable error) {
            return Optional.empty();
        }

        @Override
        protected List<Object> nativeClients() {
            return List.of();
        }
    }
}
