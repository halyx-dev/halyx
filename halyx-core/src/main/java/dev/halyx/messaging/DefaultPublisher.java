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
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.messaging.spi.PublisherDriver;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** The portable publisher: validation, capability checks, claim checks and batching over a driver. */
public final class DefaultPublisher implements Publisher {

    static final String SERVICE = "messaging";
    static final String RESERVED_PREFIX = "halyx-";

    private final PublisherDriver driver;
    private final TopicConfig config;
    private final InterceptorChain interceptors;
    private final AsyncPublisher async = new Async(InterceptorChain.blocking());

    public DefaultPublisher(PublisherDriver driver, TopicConfig config, InterceptorChain interceptors) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.config = Objects.requireNonNull(config, "config");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
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
    public PublishResult publish(OutgoingMessage message) {
        return publishAll(List.of(Objects.requireNonNull(message, "message"))).getFirst();
    }

    @Override
    public List<PublishResult> publishAll(List<OutgoingMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        List<OutgoingMessage> prepared = new ArrayList<>(messages.size());
        for (OutgoingMessage message : messages) {
            prepared.add(prepare(Objects.requireNonNull(message, "message")));
        }
        List<PublishResult> results = new ArrayList<>(prepared.size());
        int size = Math.max(1, driver.maxBatchSize());
        for (int from = 0; from < prepared.size(); from += size) {
            List<OutgoingMessage> batch = prepared.subList(from, Math.min(from + size, prepared.size()));
            // A retried publish may deliver twice unless the provider deduplicates by id.
            boolean idempotent = batch.stream().allMatch(m -> m.deduplicationId().isPresent());
            OperationContext context = new OperationContext(SERVICE, "publish", provider(), topic(), idempotent);
            List<String> ids = interceptors.execute(context, () -> {
                try {
                    return driver.publish(batch);
                } catch (RuntimeException e) {
                    throw driver.translate(e);
                }
            });
            ids.forEach(id -> results.add(new PublishResult(id)));
        }
        return results;
    }

    private OutgoingMessage prepare(OutgoingMessage message) {
        check(message);
        var claimCheck = config.claimCheck();
        if (claimCheck.isPresent() && message.size() > claimCheck.get().thresholdBytes()) {
            String key = claimCheck.get().prefix() + UUID.randomUUID();
            claimCheck.get().store().put(BlobKey.of(key), BlobContent.ofBytes(message.bodyUnsafe()));
            return claimed(message, key);
        }
        return withinLimit(message);
    }

    private static OutgoingMessage claimed(OutgoingMessage message, String key) {
        return message.withBody(key.getBytes(StandardCharsets.UTF_8), Map.of(ClaimCheck.ATTRIBUTE, key));
    }

    private OutgoingMessage withinLimit(OutgoingMessage message) {
        if (message.size() > driver.maxMessageBytes()) {
            throw new InvalidArgumentException("Message of " + message.size() + " bytes exceeds the " + driver.maxMessageBytes()
                    + "-byte limit of " + provider() + "; configure a ClaimCheck to send large bodies through a blob store",
                    null, provider(), null);
        }
        return message;
    }

    /** Attribute names and the capabilities a message needs: checked for every message before any is sent. */
    private void check(OutgoingMessage message) {
        for (String name : message.attributes().keySet()) {
            if (name.startsWith(RESERVED_PREFIX)) {
                throw new IllegalArgumentException("Attribute names starting with '" + RESERVED_PREFIX + "' are reserved: " + name);
            }
        }
        message.orderingKey().ifPresent(k -> capabilities().require(MessagingCapability.ORDERING, provider()));
        message.deduplicationId().ifPresent(k -> capabilities().require(MessagingCapability.DEDUPLICATION, provider()));
        message.delay().ifPresent(d -> capabilities().require(MessagingCapability.DELAYED_DELIVERY, provider()));
    }

    @Override
    public AsyncPublisher async() {
        return async;
    }

    @Override
    public AsyncPublisher async(Executor blocking) {
        return blocking == InterceptorChain.blocking() ? async : new Async(Objects.requireNonNull(blocking, "blocking"));
    }

    /**
     * The asynchronous view: the same checks as the blocking publish, claim-checked bodies stored through the blob
     * store's asynchronous view, then the driver's non-blocking path, one batch after another.
     */
    private final class Async implements AsyncPublisher {

        private final Executor blocking;

        Async(Executor blocking) {
            this.blocking = blocking;
        }

        @Override
        public String provider() {
            return DefaultPublisher.this.provider();
        }

        @Override
        public String topic() {
            return DefaultPublisher.this.topic();
        }

        @Override
        public CompletableFuture<List<PublishResult>> publishAll(List<OutgoingMessage> messages) {
            List<CompletableFuture<OutgoingMessage>> prepared = new ArrayList<>();
            try {
                Objects.requireNonNull(messages, "messages");
                messages.forEach(message -> check(Objects.requireNonNull(message, "message")));
                for (OutgoingMessage message : messages) {
                    prepared.add(prepare(message));
                }
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return CompletableFuture.allOf(prepared.toArray(CompletableFuture[]::new))
                    .thenCompose(ready -> sendFrom(prepared.stream().map(CompletableFuture::join).toList(), 0,
                            new ArrayList<>()));
        }

        private CompletableFuture<OutgoingMessage> prepare(OutgoingMessage message) {
            var claimCheck = config.claimCheck();
            if (claimCheck.isPresent() && message.size() > claimCheck.get().thresholdBytes()) {
                String key = claimCheck.get().prefix() + UUID.randomUUID();
                return claimCheck.get().store().async(blocking).put(BlobKey.of(key), BlobContent.ofBytes(message.bodyUnsafe()))
                        .thenApply(stored -> claimed(message, key));
            }
            return CompletableFuture.completedFuture(withinLimit(message));
        }

        /** Sends the batch starting at {@code from}, then the next, so a failure leaves only earlier batches sent. */
        private CompletableFuture<List<PublishResult>> sendFrom(List<OutgoingMessage> prepared, int from,
                                                                List<PublishResult> results) {
            if (from >= prepared.size()) {
                return CompletableFuture.completedFuture(results);
            }
            int size = Math.max(1, driver.maxBatchSize());
            List<OutgoingMessage> batch = prepared.subList(from, Math.min(from + size, prepared.size()));
            // A retried publish may deliver twice unless the provider deduplicates by id.
            boolean idempotent = batch.stream().allMatch(m -> m.deduplicationId().isPresent());
            OperationContext context = new OperationContext(SERVICE, "publish", provider(), topic(), idempotent);
            return interceptors.executeAsync(context, () -> {
                CompletableFuture<List<String>> sent;
                try {
                    sent = driver.publishAsync(batch, blocking);
                } catch (RuntimeException e) {
                    return CompletableFuture.failedFuture(driver.translate(e));
                }
                return sent.exceptionallyCompose(error -> {
                    Throwable cause = AsyncInvocation.unwrap(error);
                    return CompletableFuture.failedFuture(cause instanceof RuntimeException r ? driver.translate(r) : cause);
                });
            }, blocking).thenCompose(ids -> {
                ids.forEach(id -> results.add(new PublishResult(id)));
                return sendFrom(prepared, from + size, results);
            });
        }

        @Override
        public Publisher blocking() {
            return DefaultPublisher.this;
        }

        @Override
        public String toString() {
            return "Async" + DefaultPublisher.this;
        }
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "Publisher[" + provider() + ":" + topic() + "]";
    }
}
