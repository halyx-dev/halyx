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
package org.kamranzafar.halyx.spring.autoconfigure.config;

import org.kamranzafar.halyx.spring.autoconfigure.HalyxProperties;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.util.List;
import java.util.Map;

/**
 * Resolves {@code spring.config.import=halyx:<store>[/<prefix>]}, where {@code <store>} names an entry
 * of {@code halyx.parameter-stores} (defined earlier in the same or a previous config file) and the
 * optional {@code <prefix>} narrows what is loaded.
 */
public class HalyxConfigDataLocationResolver implements ConfigDataLocationResolver<HalyxConfigDataResource> {

    static final String PREFIX = "halyx:";

    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return location.hasPrefix(PREFIX);
    }

    @Override
    public List<HalyxConfigDataResource> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        String spec = location.getNonPrefixedValue(PREFIX);
        int slash = spec.indexOf('/');
        String storeName = slash < 0 ? spec : spec.substring(0, slash);
        String subPrefix = slash < 0 ? "" : spec.substring(slash + 1);
        Binder binder = context.getBinder();
        Map<String, HalyxProperties.ParameterStoreProperties> stores = binder
                .bind("halyx.parameter-stores", Bindable.mapOf(String.class, HalyxProperties.ParameterStoreProperties.class))
                .orElse(Map.of());
        Map<String, HalyxProperties.Provider> providers = binder
                .bind("halyx.providers", Bindable.mapOf(String.class, HalyxProperties.Provider.class))
                .orElse(Map.of());
        HalyxProperties.ParameterStoreProperties store = stores.get(storeName);
        if (store == null) {
            throw new ConfigDataLocationNotFoundException(location, "No halyx.parameter-stores." + storeName
                    + " is defined before this import; defined: " + stores.keySet(), null);
        }
        String provider = store.provider();
        if (provider == null) {
            if (providers.size() != 1) {
                throw new IllegalStateException("halyx.parameter-stores." + storeName + ".provider is required when "
                        + providers.size() + " providers are configured " + providers.keySet());
            }
            provider = providers.keySet().iterator().next();
        }
        HalyxProperties.Provider providerProperties = providers.get(provider);
        if (providerProperties == null) {
            throw new IllegalStateException("halyx.parameter-stores." + storeName + ".provider refers to unknown provider '"
                    + provider + "'. Configured providers: " + providers.keySet());
        }
        return List.of(new HalyxConfigDataResource(location.toString(), storeName, provider,
                providerProperties.toProviderConfig(provider), store.toStoreConfig(), subPrefix, location.isOptional(),
                store.expandJson(), store.reloadInterval(), Math.max(1, store.startupAttempts())));
    }
}
