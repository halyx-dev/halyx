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
package org.kamranzafar.halyx.kafka;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.kamranzafar.halyx.capability.Capabilities;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.stream.OutgoingRecord;
import org.kamranzafar.halyx.stream.SendResult;
import org.kamranzafar.halyx.stream.spi.AbstractProducerDriver;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Sends to one topic through a Kafka producer, which non-transactional drivers share. A transactional driver's
 * producer is created on first use.
 */
final class KafkaProducerDriver extends AbstractProducerDriver {

    private final String topic;
    private final Supplier<Producer<byte[], byte[]>> factory;
    private volatile Producer<byte[], byte[]> producer;
    private final boolean transactional;
    private final long maxRecordBytes;
    private final Capabilities capabilities;

    KafkaProducerDriver(String provider, String topic, Supplier<Producer<byte[], byte[]>> factory, boolean transactional,
                        long maxRecordBytes, Capabilities capabilities) {
        super(provider);
        this.topic = topic;
        this.factory = factory;
        this.transactional = transactional;
        this.maxRecordBytes = maxRecordBytes;
        this.capabilities = capabilities;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public Capabilities capabilities() {
        return capabilities;
    }

    @Override
    public List<SendResult> send(List<OutgoingRecord> records) {
        Producer<byte[], byte[]> producer = producer();
        if (!transactional) {
            return await(sendEach(producer, records));
        }
        // One transaction at a time per transactional producer.
        synchronized (producer) {
            producer.beginTransaction();
            try {
                List<Future<RecordMetadata>> sent = sendEach(producer, records);
                producer.commitTransaction();
                return await(sent);
            } catch (RuntimeException e) {
                abortQuietly(producer, e);
                throw e;
            }
        }
    }

    private Producer<byte[], byte[]> producer() {
        Producer<byte[], byte[]> p = producer;
        if (p == null) {
            synchronized (this) {
                p = producer;
                if (p == null) {
                    p = factory.get();
                    producer = p;
                }
            }
        }
        return p;
    }

    private List<Future<RecordMetadata>> sendEach(Producer<byte[], byte[]> producer, List<OutgoingRecord> records) {
        List<Future<RecordMetadata>> sent = new ArrayList<>(records.size());
        for (OutgoingRecord record : records) {
            ProducerRecord<byte[], byte[]> kafkaRecord = new ProducerRecord<>(topic, null,
                    record.key().map(k -> k.getBytes(StandardCharsets.UTF_8)).orElse(null), record.valueUnsafe());
            record.headers().forEach((name, value) ->
                    kafkaRecord.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8))));
            sent.add(producer.send(kafkaRecord));
        }
        return sent;
    }

    private List<SendResult> await(List<Future<RecordMetadata>> sent) {
        List<SendResult> results = new ArrayList<>(sent.size());
        try {
            for (Future<RecordMetadata> future : sent) {
                RecordMetadata metadata = future.get();
                results.add(new SendResult(metadata.topic(), metadata.partition(), metadata.offset(),
                        Instant.ofEpochMilli(metadata.timestamp())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new org.apache.kafka.common.errors.InterruptException(e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        }
        return results;
    }

    private static void abortQuietly(Producer<byte[], byte[]> producer, RuntimeException failure) {
        try {
            producer.abortTransaction();
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
    }

    @Override
    public int partitionCount() {
        return producer().partitionsFor(topic).size();
    }

    @Override
    public long maxRecordBytes() {
        return maxRecordBytes;
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return KafkaErrors.translate(error, provider);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(producer());
    }
}
