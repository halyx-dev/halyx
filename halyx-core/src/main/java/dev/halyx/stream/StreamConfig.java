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

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where and how to reach a stream cluster: Amazon MSK, Azure Event Hubs (through its Kafka endpoint) or Google
 * Cloud Managed Service for Apache Kafka.
 *
 * <pre>{@code
 * StreamConfig events = StreamConfig.builder("boot-abc123.c2.kafka-serverless.eu-west-2.amazonaws.com:9098")
 *         .auth(StreamAuth.iam())
 *         .build();
 * }</pre>
 *
 * Producers on the same cluster with the same settings share one connection.
 */
public final class StreamConfig {

    private final String bootstrapServers;
    private final StreamAuth auth;
    private final Map<String, String> properties;

    private StreamConfig(Builder b) {
        this.bootstrapServers = b.bootstrapServers;
        this.auth = b.auth;
        this.properties = Map.copyOf(b.properties);
    }

    /** @param bootstrapServers comma-separated {@code host:port} pairs */
    public static Builder builder(String bootstrapServers) {
        return new Builder(bootstrapServers);
    }

    /** Comma-separated {@code host:port} pairs. */
    public String bootstrapServers() {
        return bootstrapServers;
    }

    /** The bootstrap servers one by one. */
    public List<String> bootstrapServerList() {
        return Arrays.stream(bootstrapServers.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public StreamAuth auth() {
        return auth;
    }

    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, String> properties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof StreamConfig other && bootstrapServers.equals(other.bootstrapServers)
                && auth.equals(other.auth) && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bootstrapServers, auth, properties);
    }

    @Override
    public String toString() {
        return "StreamConfig[bootstrapServers=" + bootstrapServers + ", auth=" + auth + "]";
    }

    public static final class Builder {
        private final String bootstrapServers;
        private StreamAuth auth = StreamAuth.none();
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(String bootstrapServers) {
            Objects.requireNonNull(bootstrapServers, "bootstrapServers");
            if (bootstrapServers.isBlank()) {
                throw new IllegalArgumentException("bootstrapServers must not be blank");
            }
            this.bootstrapServers = bootstrapServers.trim();
        }

        public Builder auth(StreamAuth auth) {
            this.auth = Objects.requireNonNull(auth, "auth");
            return this;
        }

        /**
         * Provider and driver settings, such as {@code aws.msk.region}, or Kafka client settings prefixed with
         * {@code kafka.}, such as {@code kafka.linger.ms}.
         */
        public Builder property(String key, String value) {
            Objects.requireNonNull(key, "key");
            if (Objects.requireNonNull(value, "value").isBlank()) {
                properties.remove(key); // blank is unset, as an empty ${VAR:} in Spring configuration gives
            } else {
                properties.put(key, value);
            }
            return this;
        }

        public StreamConfig build() {
            return new StreamConfig(this);
        }
    }
}
