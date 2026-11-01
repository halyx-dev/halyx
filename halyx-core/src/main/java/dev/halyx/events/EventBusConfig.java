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
package dev.halyx.events;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * An event bus to publish to: an EventBridge event bus (name or ARN), an Event Grid namespace topic (with
 * {@code azure.eventgrid.endpoint}), or an Eventarc Advanced message bus (its name, or full resource name).
 * Provider settings go in {@link Builder#property}.
 */
public final class EventBusConfig {

    private final String name;
    private final Map<String, String> properties;
    private final Map<String, Supplier<String>> suppliedProperties;

    private EventBusConfig(Builder b) {
        this.name = b.name;
        this.properties = Map.copyOf(b.properties);
        this.suppliedProperties = Map.copyOf(b.suppliedProperties);
    }

    public static EventBusConfig of(String name) {
        return builder(name).build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
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
        return o instanceof EventBusConfig other && Objects.equals(name, other.name)
                && Objects.equals(properties, other.properties)
                && Objects.equals(suppliedProperties, other.suppliedProperties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, properties, suppliedProperties);
    }

    @Override
    public String toString() {
        return "EventBusConfig[" + name + "]";
    }

    public static final class Builder {
        private final String name;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final Map<String, Supplier<String>> suppliedProperties = new LinkedHashMap<>();

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Event bus name must not be blank");
            }
            this.name = name;
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

        public EventBusConfig build() {
            return new EventBusConfig(this);
        }
    }
}
