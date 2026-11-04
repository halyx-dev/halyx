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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * How to use one key of a {@link KeyStore}. Options with the same settings are equal, so a store hands out one
 * {@link CryptoKey} per name and options.
 */
public final class KeyOptions {

    private static final KeyOptions DEFAULTS = builder().build();

    private final SigningAlgorithm signingAlgorithm;
    private final String version;
    private final KeyPurpose purpose;
    private final Map<String, String> properties;

    private KeyOptions(Builder b) {
        this.signingAlgorithm = b.signingAlgorithm;
        this.version = b.version;
        this.purpose = b.purpose;
        this.properties = Map.copyOf(b.properties);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static KeyOptions defaults() {
        return DEFAULTS;
    }

    /**
     * The algorithm to sign with. Required for RSA keys, which can sign with PSS or PKCS #1; derived from the curve
     * for EC keys.
     */
    public Optional<SigningAlgorithm> signingAlgorithm() {
        return Optional.ofNullable(signingAlgorithm);
    }

    /** A pinned key version for encryption and signing; by default the current one. */
    public Optional<String> version() {
        return Optional.ofNullable(version);
    }

    /**
     * What the key must be usable for, checked against its metadata on first use. For a key with several purposes,
     * such as an Azure RSA key, it also limits this handle to that purpose.
     */
    public Optional<KeyPurpose> purpose() {
        return Optional.ofNullable(purpose);
    }

    /** Provider settings for this key, such as {@code azure.storage.encryption-scope}. */
    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, String> properties() {
        return properties;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof KeyOptions other && signingAlgorithm == other.signingAlgorithm
                && Objects.equals(version, other.version) && purpose == other.purpose
                && properties.equals(other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(signingAlgorithm, version, purpose, properties);
    }

    @Override
    public String toString() {
        return "KeyOptions[" + (signingAlgorithm == null ? "" : signingAlgorithm + " ")
                + (version == null ? "" : "version " + version + " ") + (purpose == null ? "" : purpose) + "]";
    }

    public static final class Builder {
        private SigningAlgorithm signingAlgorithm;
        private String version;
        private KeyPurpose purpose;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder signingAlgorithm(SigningAlgorithm algorithm) {
            this.signingAlgorithm = Objects.requireNonNull(algorithm, "algorithm");
            return this;
        }

        /** Blank is unset, as an empty {@code ${VAR:}} in Spring configuration gives. */
        public Builder version(String version) {
            Objects.requireNonNull(version, "version");
            this.version = version.isBlank() ? null : version;
            return this;
        }

        public Builder purpose(KeyPurpose purpose) {
            this.purpose = Objects.requireNonNull(purpose, "purpose");
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

        public KeyOptions build() {
            return new KeyOptions(this);
        }
    }
}
