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

import dev.halyx.messaging.ClaimCheck;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A topic to send to, on a cluster. The topic must already exist: Halyx never creates topics.
 *
 * <pre>{@code
 * StreamProducer orders = halyx.provider("aws").streamProducer(StreamProducerConfig.builder(events, "orders").build());
 * }</pre>
 */
public final class StreamProducerConfig {

    private final StreamConfig cluster;
    private final String topic;
    private final ClaimCheck claimCheck;
    private final boolean idempotent;
    private final boolean transactional;
    private final Map<String, String> properties;

    private StreamProducerConfig(Builder b) {
        this.cluster = b.cluster;
        this.topic = b.topic;
        this.claimCheck = b.claimCheck;
        this.idempotent = b.idempotent || b.transactional;
        this.transactional = b.transactional;
        this.properties = Map.copyOf(b.properties);
    }

    public static StreamProducerConfig of(StreamConfig cluster, String topic) {
        return builder(cluster, topic).build();
    }

    public static Builder builder(StreamConfig cluster, String topic) {
        return new Builder(cluster, topic);
    }

    public StreamConfig cluster() {
        return cluster;
    }

    public String topic() {
        return topic;
    }

    public Optional<ClaimCheck> claimCheck() {
        return Optional.ofNullable(claimCheck);
    }

    /** Whether the broker drops records the producer resent after a failure; implied by {@link #transactional()}. */
    public boolean idempotent() {
        return idempotent;
    }

    /** Whether {@code sendAll} is atomic. */
    public boolean transactional() {
        return transactional;
    }

    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, String> properties() {
        return properties;
    }

    /** Configurations with the same settings are equal, so contexts can share one client per configuration. */
    @Override
    public boolean equals(Object o) {
        return o instanceof StreamProducerConfig other && Objects.equals(cluster, other.cluster)
                && Objects.equals(topic, other.topic)
                && Objects.equals(claimCheck, other.claimCheck)
                && idempotent == other.idempotent
                && transactional == other.transactional
                && Objects.equals(properties, other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cluster, topic, claimCheck, idempotent, transactional, properties);
    }

    @Override
    public String toString() {
        return "StreamProducerConfig[topic=" + topic + ", cluster=" + cluster + "]";
    }

    public static final class Builder {
        private final StreamConfig cluster;
        private final String topic;
        private ClaimCheck claimCheck;
        private boolean idempotent;
        private boolean transactional;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(StreamConfig cluster, String topic) {
            this.cluster = Objects.requireNonNull(cluster, "cluster");
            Objects.requireNonNull(topic, "topic");
            if (topic.isBlank()) {
                throw new IllegalArgumentException("Topic name must not be blank");
            }
            this.topic = topic;
        }

        /** Sends values over the claim check's threshold through its blob store; consumers need the same one. */
        public Builder claimCheck(ClaimCheck claimCheck) {
            this.claimCheck = Objects.requireNonNull(claimCheck, "claimCheck");
            return this;
        }

        /** Needs {@link StreamCapability#IDEMPOTENCE}. */
        public Builder idempotent(boolean idempotent) {
            this.idempotent = idempotent;
            return this;
        }

        /** Makes {@code sendAll} all or nothing; needs {@link StreamCapability#TRANSACTIONS}. Implies idempotence. */
        public Builder transactional(boolean transactional) {
            this.transactional = transactional;
            return this;
        }

        /** Provider and driver settings, or Kafka producer settings prefixed with {@code kafka.}. */
        public Builder property(String key, String value) {
            Objects.requireNonNull(key, "key");
            if (Objects.requireNonNull(value, "value").isBlank()) {
                properties.remove(key); // blank is unset, as an empty ${VAR:} in Spring configuration gives
            } else {
                properties.put(key, value);
            }
            return this;
        }

        public StreamProducerConfig build() {
            return new StreamProducerConfig(this);
        }
    }
}
