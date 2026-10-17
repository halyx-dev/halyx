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
package dev.halyx.cache;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Where and how to reach a cache.
 *
 * <pre>{@code
 * CacheConfig sessions = CacheConfig.builder(URI.create("rediss://master.sessions.abc123.euw2.cache.amazonaws.com:6379"))
 *         .namespace("sessions")
 *         .defaultTtl(Duration.ofMinutes(30))
 *         .auth(CacheAuth.iam("sessions-app"))
 *         .property("aws.elasticache.cache-name", "sessions")
 *         .build();
 * }</pre>
 *
 * Caches with the same endpoint and auth share one connection, whatever their namespace.
 */
public final class CacheConfig {

    private final URI endpoint;
    private final String namespace;
    private final Duration defaultTtl;
    private final boolean cluster;
    private final CacheAuth auth;
    private final boolean failOpen;
    private final Map<String, String> properties;

    private CacheConfig(Builder b) {
        this.failOpen = b.failOpen;
        this.endpoint = b.endpoint;
        this.namespace = b.namespace;
        this.defaultTtl = b.defaultTtl;
        this.cluster = b.cluster;
        this.auth = b.auth;
        this.properties = Map.copyOf(b.properties);
    }

    /** @param endpoint {@code redis://host:port}, or {@code rediss://host:port} for TLS */
    public static Builder builder(URI endpoint) {
        return new Builder(endpoint);
    }

    public URI endpoint() {
        return endpoint;
    }

    public boolean tls() {
        return "rediss".equalsIgnoreCase(endpoint.getScheme());
    }

    /** Prefix applied to every key as {@code namespace:key}; empty for none. */
    public String namespace() {
        return namespace;
    }

    public Optional<Duration> defaultTtl() {
        return Optional.ofNullable(defaultTtl);
    }

    /** Whether the server runs in cluster mode (sharded across nodes). */
    public boolean cluster() {
        return cluster;
    }

    public CacheAuth auth() {
        return auth;
    }

    /** Whether an unavailable server degrades to cache misses instead of failing calls. */
    public boolean failOpen() {
        return failOpen;
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
        return o instanceof CacheConfig other && Objects.equals(endpoint, other.endpoint)
                && Objects.equals(namespace, other.namespace)
                && Objects.equals(defaultTtl, other.defaultTtl)
                && cluster == other.cluster
                && Objects.equals(auth, other.auth)
                && failOpen == other.failOpen
                && Objects.equals(properties, other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endpoint, namespace, defaultTtl, cluster, auth, failOpen, properties);
    }

    @Override
    public String toString() {
        return "CacheConfig[endpoint=" + endpoint + ", namespace=" + namespace + ", cluster=" + cluster
                + ", auth=" + auth + "]";
    }

    public static final class Builder {
        private final URI endpoint;
        private String namespace = "";
        private Duration defaultTtl;
        private boolean cluster;
        private CacheAuth auth = CacheAuth.none();
        private boolean failOpen;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(URI endpoint) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            String scheme = endpoint.getScheme();
            if (!"redis".equalsIgnoreCase(scheme) && !"rediss".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("Cache endpoint must be redis:// or rediss://: " + endpoint);
            }
            if (endpoint.getHost() == null) {
                throw new IllegalArgumentException("Cache endpoint has no host: " + endpoint);
            }
        }

        public Builder namespace(String namespace) {
            this.namespace = Objects.requireNonNull(namespace, "namespace");
            return this;
        }

        /** TTL applied when a write does not give one. Without it, entries never expire by default. */
        public Builder defaultTtl(Duration ttl) {
            this.defaultTtl = DefaultCache.requirePositive(ttl);
            return this;
        }

        public Builder cluster(boolean cluster) {
            this.cluster = cluster;
            return this;
        }

        public Builder auth(CacheAuth auth) {
            this.auth = Objects.requireNonNull(auth, "auth");
            return this;
        }

        /**
         * Treat the cache as best effort: when the server is unavailable (connection failures,
         * timeouts, throttling), reads return misses, {@code getOrLoad} calls the loader, and plain
         * writes and deletes are skipped. After repeated failures calls skip the server entirely for a
         * few seconds (circuit breaker), so a dead cache does not add a timeout to every request.
         * Operations whose result matters for correctness (putIfAbsent, replace, getAndDelete,
         * deleteIfEquals, increment, locks) still fail.
         */
        public Builder failOpen(boolean failOpen) {
            this.failOpen = failOpen;
            return this;
        }

        /** Provider-specific settings, such as {@code aws.elasticache.cache-name}. */
        public Builder property(String key, String value) {
            properties.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public CacheConfig build() {
            return new CacheConfig(this);
        }
    }
}
