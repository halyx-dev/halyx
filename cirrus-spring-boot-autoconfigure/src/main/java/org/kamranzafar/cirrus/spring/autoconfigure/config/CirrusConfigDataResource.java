package org.kamranzafar.cirrus.spring.autoconfigure.config;

import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.springframework.boot.context.config.ConfigDataResource;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** One {@code cirrus:<store>[/<prefix>]} import, resolved to its provider and store configuration. */
public final class CirrusConfigDataResource extends ConfigDataResource {

    private final String location;
    private final String providerName;
    private final ProviderConfig providerConfig;
    private final ParameterStoreConfig storeConfig;
    private final String subPrefix;
    private final String storeName;
    private final boolean expandJson;
    private final Duration reloadInterval;
    private final int startupAttempts;

    CirrusConfigDataResource(String location, String storeName, String providerName, ProviderConfig providerConfig,
                              ParameterStoreConfig storeConfig, String subPrefix, boolean optional, boolean expandJson,
                              Duration reloadInterval, int startupAttempts) {
        super(optional);
        this.location = location;
        this.storeName = storeName;
        this.providerName = providerName;
        this.providerConfig = providerConfig;
        this.storeConfig = storeConfig;
        this.subPrefix = subPrefix;
        this.expandJson = expandJson;
        this.reloadInterval = reloadInterval;
        this.startupAttempts = startupAttempts;
    }

    public String storeName() {
        return storeName;
    }

    boolean expandJson() {
        return expandJson;
    }

    Optional<Duration> reloadInterval() {
        return Optional.ofNullable(reloadInterval);
    }

    int startupAttempts() {
        return startupAttempts;
    }

    public String providerName() {
        return providerName;
    }

    ProviderConfig providerConfig() {
        return providerConfig;
    }

    public ParameterStoreConfig storeConfig() {
        return storeConfig;
    }

    public String subPrefix() {
        return subPrefix;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CirrusConfigDataResource r && r.location.equals(location);
    }

    @Override
    public int hashCode() {
        return Objects.hash(location);
    }

    @Override
    public String toString() {
        return location;
    }
}
