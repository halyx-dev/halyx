package org.kamranzafar.stratum.parameters;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which store to use and how.
 *
 * <pre>{@code
 * ParameterStore secrets = stratum.provider("aws").parameterStore(
 *         ParameterStoreConfig.secrets().prefix("orders").cacheTtl(Duration.ofMinutes(5)).build());
 * String password = secrets.require("db/password");   // Secrets Manager secret "orders/db/password"
 * }</pre>
 */
public final class ParameterStoreConfig {

    private final ParameterKind kind;
    private final String location;
    private final String prefix;
    private final Duration cacheTtl;
    private final Map<String, String> properties;

    private ParameterStoreConfig(Builder b) {
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

    @Override
    public String toString() {
        return "ParameterStoreConfig[kind=" + kind + ", location=" + location + ", prefix=" + prefix + "]";
    }

    public static final class Builder {
        private final ParameterKind kind;
        private String location;
        private String prefix = "";
        private Duration cacheTtl = Duration.ZERO;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(ParameterKind kind) {
            this.kind = Objects.requireNonNull(kind, "kind");
        }

        public Builder location(String location) {
            this.location = Objects.requireNonNull(location, "location");
            return this;
        }

        public Builder prefix(String prefix) {
            this.prefix = prefix.isEmpty() ? "" : ParameterName.validate(prefix);
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
            properties.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public ParameterStoreConfig build() {
            return new ParameterStoreConfig(this);
        }
    }
}
