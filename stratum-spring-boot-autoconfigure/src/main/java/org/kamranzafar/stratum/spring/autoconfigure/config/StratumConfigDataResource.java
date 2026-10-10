package org.kamranzafar.stratum.spring.autoconfigure.config;

import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.springframework.boot.context.config.ConfigDataResource;

import java.util.Objects;

/** One {@code stratum:<store>[/<prefix>]} import, resolved to its provider and store configuration. */
public final class StratumConfigDataResource extends ConfigDataResource {

    private final String location;
    private final String providerName;
    private final ProviderConfig providerConfig;
    private final ParameterStoreConfig storeConfig;
    private final String subPrefix;

    StratumConfigDataResource(String location, String providerName, ProviderConfig providerConfig,
                              ParameterStoreConfig storeConfig, String subPrefix, boolean optional) {
        super(optional);
        this.location = location;
        this.providerName = providerName;
        this.providerConfig = providerConfig;
        this.storeConfig = storeConfig;
        this.subPrefix = subPrefix;
    }

    String providerName() {
        return providerName;
    }

    ProviderConfig providerConfig() {
        return providerConfig;
    }

    ParameterStoreConfig storeConfig() {
        return storeConfig;
    }

    String subPrefix() {
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
