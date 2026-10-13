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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A message to publish: a body, string attributes and optional delivery controls. */
public final class OutgoingMessage {

    private final byte[] body;
    private final Map<String, String> attributes;
    private final String orderingKey;
    private final String deduplicationId;
    private final Duration delay;

    private OutgoingMessage(Builder b) {
        this.body = b.body;
        this.attributes = Map.copyOf(b.attributes);
        this.orderingKey = b.orderingKey;
        this.deduplicationId = b.deduplicationId;
        this.delay = b.delay;
    }

    public static OutgoingMessage of(String body) {
        return builder(body).build();
    }

    public static Builder builder(String body) {
        return builder(Objects.requireNonNull(body, "body").getBytes(StandardCharsets.UTF_8));
    }

    public static Builder builder(byte[] body) {
        return new Builder(body);
    }

    /** A copy with a different body and the same attributes and controls (used for claim checks). */
    public OutgoingMessage withBody(byte[] newBody, Map<String, String> extraAttributes) {
        Builder b = new Builder(newBody).attributes(attributes).attributes(extraAttributes);
        b.orderingKey = orderingKey;
        b.deduplicationId = deduplicationId;
        b.delay = delay;
        return b.build();
    }

    public byte[] body() {
        return body.clone();
    }

    /** The body without copying; callers must not modify it. */
    public byte[] bodyUnsafe() {
        return body;
    }

    public int size() {
        return body.length;
    }

    public Map<String, String> attributes() {
        return attributes;
    }

    public Optional<String> orderingKey() {
        return Optional.ofNullable(orderingKey);
    }

    public Optional<String> deduplicationId() {
        return Optional.ofNullable(deduplicationId);
    }

    public Optional<Duration> delay() {
        return Optional.ofNullable(delay);
    }

    public static final class Builder {
        private final byte[] body;
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private String orderingKey;
        private String deduplicationId;
        private Duration delay;

        private Builder(byte[] body) {
            this.body = Objects.requireNonNull(body, "body").clone();
        }

        /**
         * A string attribute (SNS/SQS message attribute, Service Bus application property, Pub/Sub
         * attribute). Names starting with {@code halyx-} are reserved.
         */
        public Builder attribute(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Attribute names must not be blank");
            }
            attributes.put(name, value);
            return this;
        }

        public Builder attributes(Map<String, String> entries) {
            entries.forEach(this::attribute);
            return this;
        }

        /** Messages with the same key are delivered in order; needs {@link MessagingCapability#ORDERING}. */
        public Builder orderingKey(String key) {
            this.orderingKey = requireText(key, "orderingKey");
            return this;
        }

        /** Drops a repeat of this id within the provider's window; needs {@link MessagingCapability#DEDUPLICATION}. */
        public Builder deduplicationId(String id) {
            this.deduplicationId = requireText(id, "deduplicationId");
            return this;
        }

        /** Holds delivery back; needs {@link MessagingCapability#DELAYED_DELIVERY}. */
        public Builder delay(Duration delay) {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay must not be negative");
            }
            this.delay = delay.isZero() ? null : delay;
            return this;
        }

        public OutgoingMessage build() {
            return new OutgoingMessage(this);
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }
    }
}
