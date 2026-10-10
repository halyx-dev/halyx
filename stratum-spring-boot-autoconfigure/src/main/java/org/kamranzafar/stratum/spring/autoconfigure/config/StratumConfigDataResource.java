package org.kamranzafar.stratum.spring.autoconfigure.config;

import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.springframework.boot.context.config.ConfigDataResource;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** One {@code stratum:<store>[/<prefix>]} import, resolved to its provider and store configuration. */
public final class StratumConfigDataResource extends ConfigDataResource {

    private final String location;
    private final String providerName;
    private final ProviderConfig providerConfig;
    private final ParameterStoreConfig storeConfig;
    private final String subPrefix;
    private final String storeName;
    private final boolean expandJson;
    private final Duration reloadInterval;
    private final int startupAttempts;

    StratumConfigDataResource(String location, String storeName, String providerName, ProviderConfig providerConfig,
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
        return other instanceof StratumConfigDataResource r && r.location.equals(location);
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
