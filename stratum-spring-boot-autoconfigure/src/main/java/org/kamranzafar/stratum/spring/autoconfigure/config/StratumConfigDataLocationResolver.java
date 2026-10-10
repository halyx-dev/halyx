package org.kamranzafar.stratum.spring.autoconfigure.config;

import org.kamranzafar.stratum.spring.autoconfigure.StratumProperties;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.util.List;
import java.util.Map;

/**
 * Resolves {@code spring.config.import=stratum:<store>[/<prefix>]}, where {@code <store>} names an entry
 * of {@code stratum.parameter-stores} (defined earlier in the same or a previous config file) and the
 * optional {@code <prefix>} narrows what is loaded.
 */
public class StratumConfigDataLocationResolver implements ConfigDataLocationResolver<StratumConfigDataResource> {

    static final String PREFIX = "stratum:";

    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return location.hasPrefix(PREFIX);
    }

    @Override
    public List<StratumConfigDataResource> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        String spec = location.getNonPrefixedValue(PREFIX);
        int slash = spec.indexOf('/');
        String storeName = slash < 0 ? spec : spec.substring(0, slash);
        String subPrefix = slash < 0 ? "" : spec.substring(slash + 1);
        Binder binder = context.getBinder();
        Map<String, StratumProperties.ParameterStoreProperties> stores = binder
                .bind("stratum.parameter-stores", Bindable.mapOf(String.class, StratumProperties.ParameterStoreProperties.class))
                .orElse(Map.of());
        Map<String, StratumProperties.Provider> providers = binder
                .bind("stratum.providers", Bindable.mapOf(String.class, StratumProperties.Provider.class))
                .orElse(Map.of());
        StratumProperties.ParameterStoreProperties store = stores.get(storeName);
        if (store == null) {
            throw new ConfigDataLocationNotFoundException(location, "No stratum.parameter-stores." + storeName
                    + " is defined before this import; defined: " + stores.keySet(), null);
        }
        String provider = store.provider();
        if (provider == null) {
            if (providers.size() != 1) {
                throw new IllegalStateException("stratum.parameter-stores." + storeName + ".provider is required when "
                        + providers.size() + " providers are configured " + providers.keySet());
            }
            provider = providers.keySet().iterator().next();
        }
        StratumProperties.Provider providerProperties = providers.get(provider);
        if (providerProperties == null) {
            throw new IllegalStateException("stratum.parameter-stores." + storeName + ".provider refers to unknown provider '"
                    + provider + "'. Configured providers: " + providers.keySet());
        }
        return List.of(new StratumConfigDataResource(location.toString(), provider,
                providerProperties.toProviderConfig(provider), store.toStoreConfig(), subPrefix, location.isOptional()));
    }
}
