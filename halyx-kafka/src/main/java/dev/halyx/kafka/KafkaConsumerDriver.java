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
package dev.halyx.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.stream.spi.AbstractConsumerDriver;
import dev.halyx.stream.spi.DriverRecord;
import dev.halyx.stream.spi.Partition;
import dev.halyx.stream.spi.RebalanceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** One consumer group member on one topic, over a Kafka consumer it owns. */
final class KafkaConsumerDriver extends AbstractConsumerDriver {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConsumerDriver.class);
    private static final byte[] EMPTY = new byte[0];

    private final String topic;
    private final String group;
    private final Consumer<byte[], byte[]> consumer;
    private final Capabilities capabilities;
    private boolean closed;

    KafkaConsumerDriver(String provider, String topic, String group, Consumer<byte[], byte[]> consumer,
                        Capabilities capabilities) {
        super(provider);
        this.topic = topic;
        this.group = group;
        this.consumer = consumer;
        this.capabilities = capabilities;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public String group() {
        return group;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public void subscribe(RebalanceListener listener) {
        consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    listener.onRevoked(portable(partitions));
                }
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    listener.onAssigned(portable(partitions));
                }
            }

            @Override
            public void onPartitionsLost(Collection<TopicPartition> partitions) {
                if (!partitions.isEmpty()) {
                    listener.onLost(portable(partitions));
                }
            }
        });
    }

    @Override
    public List<DriverRecord> poll(Duration wait) {
        try {
            List<DriverRecord> records = new ArrayList<>();
            for (ConsumerRecord<byte[], byte[]> record : consumer.poll(wait)) {
                records.add(portable(record));
            }
            return records;
        } catch (WakeupException e) {
            return List.of();
        }
    }

    private static DriverRecord portable(ConsumerRecord<byte[], byte[]> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), header.value() == null ? "" : new String(header.value(), StandardCharsets.UTF_8));
        }
        return new DriverRecord(new Partition(record.topic(), record.partition()), record.offset(),
                record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8),
                record.value() == null ? EMPTY : record.value(), headers,
                record.timestamp() < 0 ? null : Instant.ofEpochMilli(record.timestamp()));
    }

    @Override
    public Set<Partition> assignment() {
        return new HashSet<>(portable(consumer.assignment()));
    }

    @Override
    public void seek(Partition partition, long offset) {
        consumer.seek(new TopicPartition(partition.topic(), partition.partition()), offset);
    }

    @Override
    public void pause(Collection<Partition> partitions) {
        consumer.pause(kafka(partitions));
    }

    @Override
    public void resume(Collection<Partition> partitions) {
        consumer.resume(kafka(partitions));
    }

    @Override
    public void commit(Map<Partition, Long> nextOffsets) {
        consumer.commitSync(offsets(nextOffsets));
    }

    @Override
    public void commitAsync(Map<Partition, Long> nextOffsets) {
        consumer.commitAsync(offsets(nextOffsets), (offsets, error) -> {
            if (error != null) {
                LOG.debug("Asynchronous commit on {}/{} failed; a later commit covers it", topic, group, error);
            }
        });
    }

    @Override
    public void wakeup() {
        consumer.wakeup();
    }

    /** Leaves the group; also called when the provider connection closes, so only the first call counts. */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            consumer.close();
        }
    }

    private static Map<TopicPartition, OffsetAndMetadata> offsets(Map<Partition, Long> nextOffsets) {
        Map<TopicPartition, OffsetAndMetadata> offsets = new LinkedHashMap<>();
        nextOffsets.forEach((p, next) -> offsets.put(new TopicPartition(p.topic(), p.partition()), new OffsetAndMetadata(next)));
        return offsets;
    }

    private static List<TopicPartition> kafka(Collection<Partition> partitions) {
        return partitions.stream().map(p -> new TopicPartition(p.topic(), p.partition())).toList();
    }

    private static List<Partition> portable(Collection<TopicPartition> partitions) {
        return partitions.stream().map(p -> new Partition(p.topic(), p.partition())).toList();
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return KafkaErrors.translate(error, provider);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(consumer);
    }
}
