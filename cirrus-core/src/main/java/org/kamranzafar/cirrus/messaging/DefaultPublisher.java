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
package org.kamranzafar.cirrus.messaging;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.interceptor.OperationContext;
import org.kamranzafar.cirrus.messaging.spi.PublisherDriver;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobKey;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** The portable publisher: validation, capability checks, claim checks and batching over a driver. */
public final class DefaultPublisher implements Publisher {

    static final String SERVICE = "messaging";
    static final String RESERVED_PREFIX = "cirrus-";

    private final PublisherDriver driver;
    private final TopicConfig config;
    private final InterceptorChain interceptors;

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
        for (String name : message.attributes().keySet()) {
            if (name.startsWith(RESERVED_PREFIX)) {
                throw new IllegalArgumentException("Attribute names starting with '" + RESERVED_PREFIX + "' are reserved: " + name);
            }
        }
        message.orderingKey().ifPresent(k -> capabilities().require(MessagingCapability.ORDERING, provider()));
        message.deduplicationId().ifPresent(k -> capabilities().require(MessagingCapability.DEDUPLICATION, provider()));
        message.delay().ifPresent(d -> capabilities().require(MessagingCapability.DELAYED_DELIVERY, provider()));
        var claimCheck = config.claimCheck();
        if (claimCheck.isPresent() && message.size() > claimCheck.get().thresholdBytes()) {
            String key = claimCheck.get().prefix() + UUID.randomUUID();
            claimCheck.get().store().put(BlobKey.of(key), BlobContent.ofBytes(message.bodyUnsafe()));
            return message.withBody(key.getBytes(StandardCharsets.UTF_8), Map.of(ClaimCheck.ATTRIBUTE, key));
        }
        if (message.size() > driver.maxMessageBytes()) {
            throw new InvalidArgumentException("Message of " + message.size() + " bytes exceeds the " + driver.maxMessageBytes()
                    + "-byte limit of " + provider() + "; configure a ClaimCheck to send large bodies through a blob store",
                    null, provider(), null);
        }
        return message;
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
