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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A subscription (or queue) to consume from. The name is the provider's: an SQS queue name or URL (the
 * queue an SNS topic delivers to), a Service Bus queue or {@code <topic>/subscriptions/<name>}, a Pub/Sub
 * subscription id. It must already exist; its native dead-letter settings (SQS redrive policy, Service
 * Bus max delivery count, Pub/Sub dead letter policy) decide where repeatedly failing messages go.
 */
public final class SubscriptionConfig {

    private final String name;
    private final String topic;
    private final ClaimCheck claimCheck;
    private final Map<String, String> properties;
    private final Map<String, Supplier<String>> suppliedProperties;

    private SubscriptionConfig(Builder b) {
        this.name = b.name;
        this.topic = b.topic;
        this.claimCheck = b.claimCheck;
        this.properties = Map.copyOf(b.properties);
        this.suppliedProperties = Map.copyOf(b.suppliedProperties);
    }

    public static SubscriptionConfig of(String name) {
        return builder(name).build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    /** The topic the subscription receives from, for providers that bind subscriptions themselves (memory). */
    public Optional<String> topic() {
        return Optional.ofNullable(topic);
    }

    public Optional<ClaimCheck> claimCheck() {
        return Optional.ofNullable(claimCheck);
    }

    /** The property's value; a supplied property is asked now. */
    public Optional<String> property(String key) {
        String value = properties.get(key);
        return value != null ? Optional.of(value) : Optional.ofNullable(suppliedProperties.get(key)).map(Supplier::get);
    }

    /**
     * The property as a supplier to ask each time the value is used: the one given to
     * {@link Builder#property(String, Supplier)}, or one returning the fixed value.
     */
    public Optional<Supplier<String>> propertySupplier(String key) {
        String value = properties.get(key);
        return value != null ? Optional.of(() -> value) : Optional.ofNullable(suppliedProperties.get(key));
    }

    /** The properties with fixed values. */
    public Map<String, String> properties() {
        return properties;
    }

    /** The properties given as suppliers. */
    public Map<String, Supplier<String>> suppliedProperties() {
        return suppliedProperties;
    }

    /** Configurations with the same settings are equal, so contexts can share one client per configuration. */
    @Override
    public boolean equals(Object o) {
        return o instanceof SubscriptionConfig other && Objects.equals(name, other.name)
                && Objects.equals(topic, other.topic)
                && Objects.equals(claimCheck, other.claimCheck)
                && Objects.equals(properties, other.properties)
                && Objects.equals(suppliedProperties, other.suppliedProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, topic, claimCheck, properties, suppliedProperties);
    }

    @Override
    public String toString() {
        return "SubscriptionConfig[" + name + "]";
    }

    public static final class Builder {
        private final String name;
        private String topic;
        private ClaimCheck claimCheck;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final Map<String, Supplier<String>> suppliedProperties = new LinkedHashMap<>();

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Subscription name must not be blank");
            }
            this.name = name;
        }

        public Builder topic(String topic) {
            this.topic = Objects.requireNonNull(topic, "topic");
            return this;
        }

        /** Must match the publisher's claim check (same store and prefix) to resolve offloaded bodies. */
        public Builder claimCheck(ClaimCheck claimCheck) {
            this.claimCheck = Objects.requireNonNull(claimCheck, "claimCheck");
            return this;
        }

        public Builder property(String key, String value) {
            Objects.requireNonNull(key, "key");
            suppliedProperties.remove(key);
            if (Objects.requireNonNull(value, "value").isBlank()) {
                properties.remove(key); // blank is unset, as an empty ${VAR:} in Spring configuration gives
            } else {
                properties.put(key, value);
            }
            return this;
        }

        /**
         * A property whose value is asked for each time it's used, such as an access key from a parameter store, so a
         * rotated key takes effect without a restart:
         * <pre>{@code property(AzureProperties.EVENT_GRID_ACCESS_KEY, () -> secrets.require("orders-events-key"))}</pre>
         * A cached store keeps this cheap. How often a driver asks depends on the setting; the provider modules say.
         */
        public Builder property(String key, Supplier<String> value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            properties.remove(key);
            suppliedProperties.put(key, value);
            return this;
        }

        public SubscriptionConfig build() {
            return new SubscriptionConfig(this);
        }
    }
}
