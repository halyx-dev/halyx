package org.kamranzafar.stratum.config;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * constants by each provider module (for example {@code AwsProperties.S3_PATH_STYLE}).
 */
public final class ProviderConfig {

    private final String providerId;
    private final String region;
    private final URI endpoint;
    private final Credentials credentials;
    private final Map<String, String> properties;

    private ProviderConfig(String providerId, String region, URI endpoint, Credentials credentials,
                           Map<String, String> properties) {
        this.providerId = providerId;
        this.region = region;
        this.endpoint = endpoint;
        this.credentials = credentials;
        this.properties = Map.copyOf(properties);
    }

    public static ProviderConfig of(String providerId) {
        Objects.requireNonNull(providerId, "providerId");
        if (providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        return new ProviderConfig(providerId, null, null, Credentials.defaultChain(), Map.of());
    }

    public String providerId() {
        return providerId;
    }

    public Optional<String> region() {
        return Optional.ofNullable(region);
    }

    public ProviderConfig region(String region) {
        return new ProviderConfig(providerId, Objects.requireNonNull(region, "region"), endpoint, credentials, properties);
    }

    /** Endpoint override, used for emulators, private endpoints and sovereign clouds. */
    public Optional<URI> endpoint() {
        return Optional.ofNullable(endpoint);
    }

    public ProviderConfig endpoint(URI endpoint) {
        return new ProviderConfig(providerId, region, Objects.requireNonNull(endpoint, "endpoint"), credentials, properties);
    }

    public Credentials credentials() {
        return credentials;
    }

    public ProviderConfig credentials(Credentials credentials) {
        return new ProviderConfig(providerId, region, endpoint, Objects.requireNonNull(credentials, "credentials"), properties);
    }

    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public boolean booleanProperty(String key, boolean defaultValue) {
        return property(key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    public ProviderConfig property(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Map<String, String> copy = new LinkedHashMap<>(properties);
        copy.put(key, value);
        return new ProviderConfig(providerId, region, endpoint, credentials, copy);
    }

    public Map<String, String> properties() {
        return properties;
    }

    @Override
    public String toString() {
        return "ProviderConfig[provider=" + providerId + ", region=" + region + ", endpoint=" + endpoint
                + ", credentials=" + credentials + ", properties=" + properties + "]";
    }
}
