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
package dev.halyx.keys;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Which key store to use and how:
 * <pre>{@code
 * KeyStore keys = halyx.provider("azure").keyStore(KeyStoreConfig.builder()
 *         .location("https://acme.vault.azure.net")
 *         .prefix("orders-")
 *         .build());
 * CryptoKey data = keys.key("data");          // the Key Vault key "orders-data"
 * }</pre>
 * The location is where the keys live: the Key Vault URL on Azure, the key ring
 * ({@code projects/.../locations/.../keyRings/...}) on Google Cloud, the Transit mount on Vault ({@code transit} by
 * default), and nothing on AWS, whose keys belong to the provider's account and region. Configurations with the same
 * settings are equal, so contexts share one store per configuration.
 */
public final class KeyStoreConfig {

    /** Plaintext bytes per sealed segment unless configured: 64 KiB. */
    public static final int DEFAULT_SEGMENT_SIZE = 64 * 1024;
    public static final int MIN_SEGMENT_SIZE = 4 * 1024;
    public static final int MAX_SEGMENT_SIZE = Hlxs.MAX_SEGMENT_SIZE;
    /** The most messages one data key may seal under {@link Builder#dataKeyReuse}. */
    public static final long MAX_MESSAGES_PER_DATA_KEY = 1L << 32;

    private final String location;
    private final String prefix;
    private final boolean nativeNames;
    private final int segmentSize;
    private final DataKeyReuse dataKeyReuse;
    private final DataKeyCache dataKeyCache;
    private final Map<String, String> properties;

    private KeyStoreConfig(Builder b) {
        this.location = b.location;
        this.prefix = b.prefix;
        this.nativeNames = b.nativeNames;
        this.segmentSize = b.segmentSize;
        this.dataKeyReuse = b.dataKeyReuse;
        this.dataKeyCache = b.dataKeyCache;
        this.properties = Map.copyOf(b.properties);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The provider's default store: on AWS, its account and region's keys; on Vault, the {@code transit} mount. */
    public static KeyStoreConfig defaults() {
        return builder().build();
    }

    public Optional<String> location() {
        return Optional.ofNullable(location);
    }

    /** Prepended to every key name; empty for none. */
    public String prefix() {
        return prefix;
    }

    /**
     * Whether names are the provider's own, used as given: a key ID, ARN or alias on AWS, a full crypto key name on
     * Google Cloud. No portable-name check, and the prefix is a plain string prefix.
     */
    public boolean nativeNames() {
        return nativeNames;
    }

    public int segmentSize() {
        return segmentSize;
    }

    /** Whether, and how long, encryption reuses one data key; off by default. */
    public Optional<DataKeyReuse> dataKeyReuse() {
        return Optional.ofNullable(dataKeyReuse);
    }

    /** Whether, and how long, decryption keeps unwrapped data keys; off by default. */
    public Optional<DataKeyCache> dataKeyCache() {
        return Optional.ofNullable(dataKeyCache);
    }

    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, String> properties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KeyStoreConfig other && Objects.equals(location, other.location)
                && prefix.equals(other.prefix) && nativeNames == other.nativeNames && segmentSize == other.segmentSize
                && Objects.equals(dataKeyReuse, other.dataKeyReuse) && Objects.equals(dataKeyCache, other.dataKeyCache)
                && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(location, prefix, nativeNames, segmentSize, dataKeyReuse, dataKeyCache, properties);
    }

    @Override
    public String toString() {
        return "KeyStoreConfig[" + (location == null ? "default" : location) + (prefix.isEmpty() ? "" : ", prefix "
                + prefix) + "]";
    }

    /**
     * Encryption reuses one data key until it is {@code maxAge} old or has sealed {@code maxMessages} messages,
     * cutting key service calls. Every message still gets its own subkey and nonces. The trade-off is that one data
     * key, and so one wrapped copy, covers more data.
     */
    public record DataKeyReuse(Duration maxAge, long maxMessages) {
        public DataKeyReuse {
            Objects.requireNonNull(maxAge, "maxAge");
            if (maxAge.isNegative() || maxAge.isZero()) {
                throw new IllegalArgumentException("maxAge must be positive");
            }
            if (maxMessages < 1 || maxMessages > MAX_MESSAGES_PER_DATA_KEY) {
                throw new IllegalArgumentException("maxMessages must be 1 to " + MAX_MESSAGES_PER_DATA_KEY);
            }
        }
    }

    /**
     * Decryption keeps up to {@code maxEntries} unwrapped data keys, each for {@code ttl}, keyed by their wrapped
     * bytes, so data sealed under one data key is opened with one key service call. Keys are held in memory only,
     * and zeroed when evicted.
     */
    public record DataKeyCache(Duration ttl, int maxEntries) {
        public DataKeyCache {
            Objects.requireNonNull(ttl, "ttl");
            if (ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("ttl must be positive");
            }
            if (maxEntries < 1) {
                throw new IllegalArgumentException("maxEntries must be positive");
            }
        }
    }

    public static final class Builder {
        private String location;
        private String prefix = "";
        private boolean nativeNames;
        private int segmentSize = DEFAULT_SEGMENT_SIZE;
        private DataKeyReuse dataKeyReuse;
        private DataKeyCache dataKeyCache;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder() {
        }

        /** Where the keys live; blank is unset, as an empty {@code ${VAR:}} in Spring configuration gives. */
        public Builder location(String location) {
            Objects.requireNonNull(location, "location");
            this.location = location.isBlank() ? null : location;
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

        /** Plaintext bytes per sealed segment, from 4 KiB to 16 MiB; 64 KiB by default. */
        public Builder segmentSize(int bytes) {
            if (bytes < MIN_SEGMENT_SIZE || bytes > MAX_SEGMENT_SIZE) {
                throw new IllegalArgumentException("segmentSize must be " + MIN_SEGMENT_SIZE + " to " + MAX_SEGMENT_SIZE);
            }
            this.segmentSize = bytes;
            return this;
        }

        /** See {@link DataKeyReuse}. */
        public Builder dataKeyReuse(Duration maxAge, long maxMessages) {
            this.dataKeyReuse = new DataKeyReuse(maxAge, maxMessages);
            return this;
        }

        /** See {@link DataKeyCache}. */
        public Builder dataKeyCache(Duration ttl, int maxEntries) {
            this.dataKeyCache = new DataKeyCache(ttl, maxEntries);
            return this;
        }

        public Builder property(String key, String value) {
            Objects.requireNonNull(key, "key");
            if (Objects.requireNonNull(value, "value").isBlank()) {
                properties.remove(key);
            } else {
                properties.put(key, value);
            }
            return this;
        }

        public KeyStoreConfig build() {
            return new KeyStoreConfig(this);
        }
    }
}
