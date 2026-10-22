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

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.SendResult;
import dev.halyx.stream.spi.AbstractProducerDriver;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Sends to one topic through a Kafka producer, which non-transactional drivers share. A transactional driver's
 * producer is created on first use.
 */
final class KafkaProducerDriver extends AbstractProducerDriver {

    private final String topic;
    private final Supplier<Producer<byte[], byte[]>> factory;
    private volatile Producer<byte[], byte[]> producer;
    /** Creates the producer once; creating a transactional one calls the broker. */
    private final ReentrantLock creating = new ReentrantLock();
    /**
     * One transaction at a time. Locks rather than monitors: both are held across broker calls, during which a
     * monitor would pin a virtual thread's carrier on Java 21.
     */
    private final ReentrantLock transactions = new ReentrantLock();
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
        transactions.lock();
        try {
            producer.beginTransaction();
            try {
                List<Future<RecordMetadata>> sent = sendEach(producer, records);
                producer.commitTransaction();
                return await(sent);
            } catch (RuntimeException e) {
                abortQuietly(producer, e);
                throw e;
            }
        } finally {
            transactions.unlock();
        }
    }

    private Producer<byte[], byte[]> producer() {
        Producer<byte[], byte[]> p = producer;
        if (p == null) {
            creating.lock();
            try {
                p = producer;
                if (p == null) {
                    p = factory.get();
                    producer = p;
                }
            } finally {
                creating.unlock();
            }
        }
        return p;
    }

    /**
     * Completes from Kafka's send callbacks, when the broker acknowledges each record, with no thread waiting. The
     * producer is made off the caller's thread on first use. A transaction runs on the blocking executor, as a whole.
     */
    @Override
    public CompletableFuture<List<SendResult>> sendAsync(List<OutgoingRecord> records, Executor blocking) {
        if (transactional) {
            return super.sendAsync(records, blocking);
        }
        Producer<byte[], byte[]> p = producer;
        CompletableFuture<Producer<byte[], byte[]>> ready = p != null ? CompletableFuture.completedFuture(p)
                : CompletableFuture.supplyAsync(this::producer, blocking);
        return ready.thenCompose(producer -> {
            List<CompletableFuture<SendResult>> sent = new ArrayList<>(records.size());
            for (OutgoingRecord record : records) {
                CompletableFuture<SendResult> result = new CompletableFuture<>();
                producer.send(toKafka(record), (metadata, error) -> {
                    if (error != null) {
                        result.completeExceptionally(error);
                    } else {
                        result.complete(new SendResult(metadata.topic(), metadata.partition(), metadata.offset(),
                                Instant.ofEpochMilli(metadata.timestamp())));
                    }
                });
                sent.add(result);
            }
            return CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new))
                    .thenApply(done -> sent.stream().map(CompletableFuture::join).toList());
        });
    }

    private List<Future<RecordMetadata>> sendEach(Producer<byte[], byte[]> producer, List<OutgoingRecord> records) {
        List<Future<RecordMetadata>> sent = new ArrayList<>(records.size());
        for (OutgoingRecord record : records) {
            sent.add(producer.send(toKafka(record)));
        }
        return sent;
    }

    private ProducerRecord<byte[], byte[]> toKafka(OutgoingRecord record) {
        ProducerRecord<byte[], byte[]> kafkaRecord = new ProducerRecord<>(topic, null,
                record.key().map(k -> k.getBytes(StandardCharsets.UTF_8)).orElse(null), record.valueUnsafe());
        record.headers().forEach((name, value) ->
                kafkaRecord.headers().add(new RecordHeader(name, value.getBytes(StandardCharsets.UTF_8))));
        return kafkaRecord;
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
