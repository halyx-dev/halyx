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
package dev.halyx.parameters;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which store to use and how.
 *
 * <pre>{@code
 * ParameterStore secrets = halyx.provider("aws").parameterStore(
 *         ParameterStoreConfig.secrets().prefix("orders").cacheTtl(Duration.ofMinutes(5)).build());
 * String password = secrets.require("db/password");   // Secrets Manager secret "orders/db/password"
 * }</pre>
 */
public final class ParameterStoreConfig {

    private final ParameterKind kind;
    private final String location;
    private final String prefix;
    private final Duration cacheTtl;
    private final boolean nativeNames;
    private final Map<String, String> properties;

    private ParameterStoreConfig(Builder b) {
        this.nativeNames = b.nativeNames;
        this.kind = b.kind;
        this.location = b.location;
        this.prefix = b.prefix;
        this.cacheTtl = b.cacheTtl;
        this.properties = Map.copyOf(b.properties);
    }

    public static Builder builder(ParameterKind kind) {
        return new Builder(kind);
    }

    public static Builder parameters() {
        return builder(ParameterKind.PARAMETERS);
    }

    public static Builder secrets() {
        return builder(ParameterKind.SECRETS);
    }

    public ParameterKind kind() {
        return kind;
    }

    /**
     * Where the store lives when the provider needs it: the Key Vault URL or App Configuration
     * endpoint on Azure, the KV mount on Vault. Empty on AWS and GCP.
     */
    public Optional<String> location() {
        return Optional.ofNullable(location);
    }

    /** Name prefix applied to every name; empty for none. */
    public String prefix() {
        return prefix;
    }

    /**
     * Whether names are the backend's own, used exactly as given: no portable-name validation, no
     * separator translation, and the prefix is a literal string prefix (include any separator in it).
     * Use it to read existing values whose names do not follow {@link ParameterName}, such as
     * {@code /app/db_password} or AWS-managed {@code rds!db-...} secrets.
     */
    public boolean nativeNames() {
        return nativeNames;
    }

    /** How long reads are cached in this JVM; zero (the default) disables caching. */
    public Duration cacheTtl() {
        return cacheTtl;
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
        return o instanceof ParameterStoreConfig other && Objects.equals(kind, other.kind)
                && Objects.equals(location, other.location)
                && Objects.equals(prefix, other.prefix)
                && Objects.equals(cacheTtl, other.cacheTtl)
                && nativeNames == other.nativeNames
                && Objects.equals(properties, other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, location, prefix, cacheTtl, nativeNames, properties);
    }

    @Override
    public String toString() {
        return "ParameterStoreConfig[kind=" + kind + ", location=" + location + ", prefix=" + prefix + "]";
    }

    public static final class Builder {
        private final ParameterKind kind;
        private String location;
        private String prefix = "";
        private Duration cacheTtl = Duration.ZERO;
        private boolean nativeNames;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(ParameterKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        public Builder location(String location) {
            this.location = Objects.requireNonNull(location, "location");
            return this;
        }

        public Builder prefix(String prefix) {
            this.prefix = Objects.requireNonNull(prefix, "prefix");
            return this;
        }

        public Builder nativeNames(boolean nativeNames) {
            this.nativeNames = nativeNames;
            return this;
        }

        public Builder cacheTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "ttl");
            if (ttl.isNegative()) {
                throw new IllegalArgumentException("cacheTtl must not be negative");
            }
            this.cacheTtl = ttl;
            return this;
        }

        public Builder property(String key, String value) {
            Objects.requireNonNull(key, "key");
            if (Objects.requireNonNull(value, "value").isBlank()) {
                properties.remove(key); // blank is unset, as an empty ${VAR:} in Spring configuration gives
            } else {
                properties.put(key, value);
            }
            return this;
        }

        public ParameterStoreConfig build() {
            if (!nativeNames && !prefix.isEmpty()) {
                ParameterName.validate(prefix);
            }
            return new ParameterStoreConfig(this);
        }
    }
}
