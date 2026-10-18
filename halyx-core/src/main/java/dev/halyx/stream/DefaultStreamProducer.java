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
package dev.halyx.stream;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.messaging.ClaimCheck;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.stream.spi.ProducerDriver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** The portable stream producer: validation, capability checks, claim checks and interceptors over a driver. */
public final class DefaultStreamProducer implements StreamProducer {

    static final String SERVICE = "stream";
    static final String RESERVED_PREFIX = "halyx-";
    /**
     * Dead-letter headers may be sent by anyone: they only describe where a record came from. The other
     * {@code halyx-} headers, such as the claim check, change how consumers read a record.
     */
    static final String DEAD_LETTER_PREFIX = "halyx-dead-letter-";

    private final ProducerDriver driver;
    private final StreamProducerConfig config;
    private final InterceptorChain interceptors;
    private final AsyncStreamProducer async = new Async(InterceptorChain.blocking());

    public DefaultStreamProducer(ProducerDriver driver, StreamProducerConfig config, InterceptorChain interceptors) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.config = Objects.requireNonNull(config, "config");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
        if (config.transactional()) {
            capabilities().require(StreamCapability.TRANSACTIONS, provider());
        } else if (config.idempotent()) {
            capabilities().require(StreamCapability.IDEMPOTENCE, provider());
        }
    }

    @Override
    public String provider() {
        return driver.provider();
    }

    @Override
    public String topic() {
        return driver.topic();
    }

    @Override
    public Capabilities capabilities() {
        return driver.capabilities();
    }

    @Override
    public SendResult send(OutgoingRecord record) {
        return sendAll(List.of(Objects.requireNonNull(record, "record"))).getFirst();
    }

    @Override
    public List<SendResult> sendAll(List<OutgoingRecord> records) {
        Objects.requireNonNull(records, "records");
        if (records.isEmpty()) {
            return List.of();
        }
        List<OutgoingRecord> prepared = new ArrayList<>(records.size());
        for (OutgoingRecord record : records) {
            prepared.add(prepare(Objects.requireNonNull(record, "record")));
        }
        // A retried send may store a record twice unless the broker deduplicates the producer's resends.
        return execute("send", config.idempotent(), () -> driver.send(prepared));
    }

    private OutgoingRecord prepare(OutgoingRecord record) {
        check(record);
        var claimCheck = config.claimCheck();
        if (claimCheck.isPresent() && record.size() > claimCheck.get().thresholdBytes()) {
            String key = claimCheck.get().prefix() + UUID.randomUUID();
            claimCheck.get().store().put(BlobKey.of(key), BlobContent.ofBytes(record.valueUnsafe()));
            return claimed(record, key);
        }
        return withinLimit(record);
    }

    private void check(OutgoingRecord record) {
        for (String name : record.headers().keySet()) {
            if (name.startsWith(RESERVED_PREFIX) && !name.startsWith(DEAD_LETTER_PREFIX)) {
                throw new IllegalArgumentException("Header names starting with '" + RESERVED_PREFIX + "' are reserved: " + name);
            }
        }
    }

    private static OutgoingRecord claimed(OutgoingRecord record, String key) {
        return record.withValue(key.getBytes(StandardCharsets.UTF_8), Map.of(ClaimCheck.ATTRIBUTE, key));
    }

    private OutgoingRecord withinLimit(OutgoingRecord record) {
        if (record.size() > driver.maxRecordBytes()) {
            throw new InvalidArgumentException("Record of " + record.size() + " bytes exceeds the " + driver.maxRecordBytes()
                    + "-byte limit of " + provider() + "; configure a ClaimCheck to send large values through a blob store",
                    null, provider(), null);
        }
        return record;
    }

    @Override
    public int partitionCount() {
        return execute("partitionCount", true, driver::partitionCount);
    }

    private <T> T execute(String operation, boolean idempotent, Supplier<T> call) {
        OperationContext context = new OperationContext(SERVICE, operation, provider(), topic(), idempotent);
        return interceptors.execute(context, () -> {
            try {
                return call.get();
            } catch (RuntimeException e) {
                throw driver.translate(e);
            }
        });
    }

    @Override
    public AsyncStreamProducer async() {
        return async;
    }

    @Override
    public AsyncStreamProducer async(Executor blocking) {
        return blocking == InterceptorChain.blocking() ? async : new Async(Objects.requireNonNull(blocking, "blocking"));
    }

    /** The asynchronous view: the same checks, claim checks through the blob store's async view, then the driver. */
    private final class Async implements AsyncStreamProducer {

        private final Executor blocking;

        Async(Executor blocking) {
            this.blocking = blocking;
        }

        @Override
        public String provider() {
            return DefaultStreamProducer.this.provider();
        }

        @Override
        public String topic() {
            return DefaultStreamProducer.this.topic();
        }

        @Override
        public CompletableFuture<List<SendResult>> sendAll(List<OutgoingRecord> records) {
            List<CompletableFuture<OutgoingRecord>> prepared = new ArrayList<>();
            try {
                Objects.requireNonNull(records, "records");
                records.forEach(record -> check(Objects.requireNonNull(record, "record")));
                for (OutgoingRecord record : records) {
                    prepared.add(prepare(record));
                }
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            if (prepared.isEmpty()) {
                return CompletableFuture.completedFuture(List.of());
            }
            return CompletableFuture.allOf(prepared.toArray(CompletableFuture[]::new)).thenCompose(ready -> {
                List<OutgoingRecord> batch = prepared.stream().map(CompletableFuture::join).toList();
                OperationContext context = new OperationContext(SERVICE, "send", provider(), topic(), config.idempotent());
                return interceptors.executeAsync(context, () -> {
                    CompletableFuture<List<SendResult>> sent;
                    try {
                        sent = driver.sendAsync(batch, blocking);
                    } catch (RuntimeException e) {
                        return CompletableFuture.failedFuture(driver.translate(e));
                    }
                    return sent.exceptionallyCompose(error -> {
                        Throwable cause = AsyncInvocation.unwrap(error);
                        return CompletableFuture.failedFuture(cause instanceof RuntimeException r ? driver.translate(r) : cause);
                    });
                }, blocking);
            });
        }

        private CompletableFuture<OutgoingRecord> prepare(OutgoingRecord record) {
            var claimCheck = config.claimCheck();
            if (claimCheck.isPresent() && record.size() > claimCheck.get().thresholdBytes()) {
                String key = claimCheck.get().prefix() + UUID.randomUUID();
                return claimCheck.get().store().async(blocking).put(BlobKey.of(key), BlobContent.ofBytes(record.valueUnsafe()))
                        .thenApply(stored -> claimed(record, key));
            }
            return CompletableFuture.completedFuture(withinLimit(record));
        }

        @Override
        public StreamProducer blocking() {
            return DefaultStreamProducer.this;
        }

        @Override
        public String toString() {
            return "Async" + DefaultStreamProducer.this;
        }
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(Objects.requireNonNull(nativeType, "nativeType"));
    }

    @Override
    public String toString() {
        return "StreamProducer[" + provider() + ":" + topic() + "]";
    }
}
