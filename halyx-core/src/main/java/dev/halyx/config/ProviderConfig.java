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
package dev.halyx.config;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Immutable configuration for one provider connection. Every "setter" returns a new instance, so a
 * base config can be safely shared and specialised:
 *
 * <pre>{@code
 * ProviderConfig aws = ProviderConfig.of("aws").region("eu-west-2");
 * ProviderConfig local = aws.endpoint(URI.create("http://localhost:4566"));
 * }</pre>
 *
 * Provider-specific settings go in {@link #property(String, String)}, with keys published as
 * constants by each provider module (for example {@code AwsProperties.S3_PATH_STYLE}). A blank value
 * is no value: it removes the setting, here and in every resource config's {@code property}. A secret, such as an
 * account key, can be given as a supplier with {@link #property(String, Supplier)}.
 */
public final class ProviderConfig {

    private final String providerId;
    private final String region;
    private final URI endpoint;
    private final Credentials credentials;
    private final Map<String, String> properties;
    private final Map<String, Supplier<String>> suppliedProperties;

    private ProviderConfig(String providerId, String region, URI endpoint, Credentials credentials,
                           Map<String, String> properties, Map<String, Supplier<String>> suppliedProperties) {
        this.providerId = providerId;
        this.region = region;
        this.endpoint = endpoint;
        this.credentials = credentials;
        this.properties = Map.copyOf(properties);
        this.suppliedProperties = Map.copyOf(suppliedProperties);
    }

    public static ProviderConfig of(String providerId) {
        Objects.requireNonNull(providerId, "providerId");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        return new ProviderConfig(providerId, null, null, Credentials.defaultChain(), Map.of(), Map.of());
    }

    public String providerId() {
        return providerId;
    }

    public Optional<String> region() {
        return Optional.ofNullable(region);
    }

    public ProviderConfig region(String region) {
        return new ProviderConfig(providerId, Objects.requireNonNull(region, "region"), endpoint, credentials, properties,
                suppliedProperties);
    }

    /** Endpoint override, used for emulators, private endpoints and sovereign clouds. */
    public Optional<URI> endpoint() {
        return Optional.ofNullable(endpoint);
    }

    public ProviderConfig endpoint(URI endpoint) {
        return new ProviderConfig(providerId, region, Objects.requireNonNull(endpoint, "endpoint"), credentials, properties,
                suppliedProperties);
    }

    public Credentials credentials() {
        return credentials;
    }

    public ProviderConfig credentials(Credentials credentials) {
        return new ProviderConfig(providerId, region, endpoint, Objects.requireNonNull(credentials, "credentials"), properties,
                suppliedProperties);
    }

    /** The property's value; a supplied property is asked now. */
    public Optional<String> property(String key) {
        String value = properties.get(key);
        return value != null ? Optional.of(value) : Optional.ofNullable(suppliedProperties.get(key)).map(Supplier::get);
    }

    /**
     * The property as a supplier to ask each time the value is used: the one given to
     * {@link #property(String, Supplier)}, or one returning the fixed value.
     */
    public Optional<Supplier<String>> propertySupplier(String key) {
        String value = properties.get(key);
        return value != null ? Optional.of(() -> value) : Optional.ofNullable(suppliedProperties.get(key));
    }

    public boolean booleanProperty(String key, boolean defaultValue) {
        return property(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    public ProviderConfig property(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Map<String, String> copy = new LinkedHashMap<>(properties);
        if (value.isBlank()) {
            copy.remove(key); // blank is unset, as an empty ${VAR:} in Spring configuration gives
        } else {
            copy.put(key, value);
        }
        Map<String, Supplier<String>> supplied = new LinkedHashMap<>(suppliedProperties);
        supplied.remove(key);
        return new ProviderConfig(providerId, region, endpoint, credentials, copy, supplied);
    }

    /**
     * A property whose value is asked for while it's in use, such as an account key from a parameter store, so a
     * rotated key takes effect without a restart:
     * <pre>{@code property(AzureProperties.COSMOS_KEY, () -> secrets.require("orders-cosmos-key"))}</pre>
     * A cached store keeps this cheap. How often a driver asks depends on the setting; the provider modules say.
     */
    public ProviderConfig property(String key, Supplier<String> value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Map<String, String> copy = new LinkedHashMap<>(properties);
        copy.remove(key);
        Map<String, Supplier<String>> supplied = new LinkedHashMap<>(suppliedProperties);
        supplied.put(key, value);
        return new ProviderConfig(providerId, region, endpoint, credentials, copy, supplied);
    }

    /** The properties with fixed values. */
    public Map<String, String> properties() {
        return properties;
    }

    /** The properties given as suppliers. */
    public Map<String, Supplier<String>> suppliedProperties() {
        return suppliedProperties;
    }

    @Override
    public String toString() {
        return "ProviderConfig[provider=" + providerId + ", region=" + region + ", endpoint=" + endpoint
                + ", credentials=" + credentials + ", properties=" + properties + "]";
    }
}
