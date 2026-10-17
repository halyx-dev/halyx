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
 * A consumer group member reading one topic. Consumers in the same group share the topic's partitions between
 * them; each group reads every record. The topic must already exist.
 *
 * <pre>{@code
 * StreamConsumer billing = halyx.provider("aws").streamConsumer(StreamConsumerConfig.builder(events, "orders", "billing")
 *         .startPosition(StartPosition.EARLIEST)
 *         .build());
 * }</pre>
 */
public final class StreamConsumerConfig {

    private final StreamConfig cluster;
    private final String topic;
    private final String group;
    private final StartPosition startPosition;
    private final boolean readCommitted;
    private final ClaimCheck claimCheck;
    private final Map<String, String> properties;

    private StreamConsumerConfig(Builder b) {
        this.cluster = b.cluster;
        this.topic = b.topic;
        this.group = b.group;
        this.startPosition = b.startPosition;
        this.readCommitted = b.readCommitted;
        this.claimCheck = b.claimCheck;
        this.properties = Map.copyOf(b.properties);
    }

    public static StreamConsumerConfig of(StreamConfig cluster, String topic, String group) {
        return builder(cluster, topic, group).build();
    }

    public static Builder builder(StreamConfig cluster, String topic, String group) {
        return new Builder(cluster, topic, group);
    }

    public StreamConfig cluster() {
        return cluster;
    }

    public String topic() {
        return topic;
    }

    /** The consumer group, whose committed positions the members share. */
    public String group() {
        return group;
    }

    public StartPosition startPosition() {
        return startPosition;
    }

    /** Whether records of aborted or still-open transactions are hidden. */
    public boolean readCommitted() {
        return readCommitted;
    }

    public Optional<ClaimCheck> claimCheck() {
        return Optional.ofNullable(claimCheck);
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
        return o instanceof StreamConsumerConfig other && Objects.equals(cluster, other.cluster)
                && Objects.equals(topic, other.topic)
                && Objects.equals(group, other.group)
                && Objects.equals(startPosition, other.startPosition)
                && readCommitted == other.readCommitted
                && Objects.equals(claimCheck, other.claimCheck)
                && Objects.equals(properties, other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cluster, topic, group, startPosition, readCommitted, claimCheck, properties);
    }

    @Override
    public String toString() {
        return "StreamConsumerConfig[topic=" + topic + ", group=" + group + ", cluster=" + cluster + "]";
    }

    public static final class Builder {
        private final StreamConfig cluster;
        private final String topic;
        private final String group;
        private StartPosition startPosition = StartPosition.LATEST;
        private boolean readCommitted;
        private ClaimCheck claimCheck;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(StreamConfig cluster, String topic, String group) {
            this.cluster = Objects.requireNonNull(cluster, "cluster");
            this.topic = requireText(topic, "topic");
            this.group = requireText(group, "group");
        }

        /** Where to start when the group has no committed position (default {@link StartPosition#LATEST}). */
        public Builder startPosition(StartPosition startPosition) {
            this.startPosition = Objects.requireNonNull(startPosition, "startPosition");
            return this;
        }

        /** Hide records of aborted and open transactions (default false, as in Kafka). */
        public Builder readCommitted(boolean readCommitted) {
            this.readCommitted = readCommitted;
            return this;
        }

        /** Must match the producer's claim check (same store and prefix) to resolve offloaded values. */
        public Builder claimCheck(ClaimCheck claimCheck) {
            this.claimCheck = Objects.requireNonNull(claimCheck, "claimCheck");
            return this;
        }

        /** Provider and driver settings, or Kafka consumer settings prefixed with {@code kafka.}. */
        public Builder property(String key, String value) {
            properties.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public StreamConsumerConfig build() {
            return new StreamConsumerConfig(this);
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
