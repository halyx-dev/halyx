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
package dev.halyx.spring.autoconfigure.config;

import dev.halyx.keys.KeyOptions;
import dev.halyx.spring.autoconfigure.HalyxProperties;
import org.springframework.boot.context.config.ConfigDataLocation;
import org.springframework.boot.context.config.ConfigDataLocationNotFoundException;
import org.springframework.boot.context.config.ConfigDataLocationResolver;
import org.springframework.boot.context.config.ConfigDataLocationResolverContext;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves {@code spring.config.import=halyx:<store>[/<prefix>]}, where {@code <store>} names an entry
 * of {@code halyx.parameter-stores} (defined earlier in the same or a previous config file) and the
 * optional {@code <prefix>} narrows what is loaded.
 * <p>
 * {@code halyx.config-import.enabled=false} (on the command line, in the environment, or in a document read before
 * the import) makes every {@code halyx:} import load nothing, optional or not, without connecting: for tests that
 * resolve a cloud profile as written, and for tools that start the application with no cloud.
 */
public class HalyxConfigDataLocationResolver implements ConfigDataLocationResolver<HalyxConfigDataResource> {

    static final String PREFIX = "halyx:";
    static final String ENABLED = "halyx.config-import.enabled";

    @Override
    public boolean isResolvable(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        return location.hasPrefix(PREFIX);
    }

    @Override
    public List<HalyxConfigDataResource> resolve(ConfigDataLocationResolverContext context, ConfigDataLocation location) {
        if (!context.getBinder().bind(ENABLED, Boolean.class).orElse(true)) {
            // A resource that loads nothing, rather than none: Spring reports a mandatory location without one
            return List.of(HalyxConfigDataResource.switchedOff(location.toString(), location.isOptional()));
        }
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
        HalyxImportKey key = store.encryptionKey() == null ? null
                : importKey(binder, "halyx.parameter-stores." + storeName, store, providers);
        return List.of(new HalyxConfigDataResource(location.toString(), storeName, provider,
                providerProperties.toProviderConfig(provider), store.toStoreConfig(), subPrefix, location.isOptional(),
                store.expandJson(), store.reloadInterval(), Math.max(1, store.startupAttempts()), key));
    }

    /**
     * The key that opens the store's values, with its key store and that store's provider. They are read before the
     * import, so a setting that only the import would resolve is refused.
     */
    private static HalyxImportKey importKey(Binder binder, String storeProperty,
                                            HalyxProperties.ParameterStoreProperties store,
                                            Map<String, HalyxProperties.Provider> providers) {
        String property = storeProperty + ".encryption-key";
        HalyxProperties.EncryptionKey reference = store.encryptionKey();
        if (reference.name() == null || reference.name().isBlank()) {
            throw new IllegalStateException(property + ".name is required: the key to open values with");
        }
        Map<String, HalyxProperties.KeyStoreProperties> keyStores = binder
                .bind("halyx.key-stores", Bindable.mapOf(String.class, HalyxProperties.KeyStoreProperties.class))
                .orElse(Map.of());
        String keyStoreName = reference.store();
        if (keyStoreName == null || keyStoreName.isBlank()) {
            if (keyStores.size() != 1) {
                throw new IllegalStateException(property + ".store is required when " + keyStores.size()
                        + " key stores are defined before this import " + new TreeSet<>(keyStores.keySet()));
            }
            keyStoreName = keyStores.keySet().iterator().next();
        }
        HalyxProperties.KeyStoreProperties keyStore = keyStores.get(keyStoreName);
        if (keyStore == null) {
            throw new IllegalStateException(property + ".store must name one of the halyx.key-stores defined before "
                    + "this import " + new TreeSet<>(keyStores.keySet()));
        }
        String keyStoreProperty = "halyx.key-stores." + keyStoreName;
        String provider = keyStore.provider();
        if (provider == null) {
            if (providers.size() != 1) {
                throw new IllegalStateException(keyStoreProperty + ".provider is required when " + providers.size()
                        + " providers are configured " + providers.keySet());
            }
            provider = providers.keySet().iterator().next();
        }
        HalyxProperties.Provider providerProperties = providers.get(provider);
        if (providerProperties == null) {
            throw new IllegalStateException(keyStoreProperty + ".provider refers to unknown provider '" + provider
                    + "'. Configured providers: " + providers.keySet());
        }
        requireResolved(binder, property, storeProperty);
        requireResolved(binder, keyStoreProperty, storeProperty);
        requireResolved(binder, "halyx.providers." + provider, storeProperty);
        return new HalyxImportKey(keyStoreName, provider, providerProperties.toProviderConfig(provider),
                keyStore.toKeyStoreConfig(keyStoreName), reference.name(), keyOptions(reference.properties()),
                store.allowPlaintext());
    }

    private static KeyOptions keyOptions(Map<String, String> properties) {
        KeyOptions.Builder options = KeyOptions.builder();
        properties.forEach(options::property);
        return options.build();
    }

    /**
     * Fails if a setting under {@code property} still holds a placeholder: Spring leaves one that nothing read so far
     * resolves as written, such as one only the import itself would set.
     */
    private static void requireResolved(Binder binder, String property, String storeProperty) {
        Set<String> unresolved = new TreeSet<>();
        binder.bind(property, Bindable.mapOf(String.class, Object.class))
                .ifBound(values -> collectUnresolved(property, values, unresolved));
        if (!unresolved.isEmpty()) {
            throw new IllegalStateException("Unresolved placeholders in " + String.join(", ", unresolved) + ": the key "
                    + "that opens " + storeProperty + " is read before its import, so its settings, its key store's and "
                    + "that store's provider's come from configuration, the environment or the platform identity, not "
                    + "from the import itself");
        }
    }

    private static void collectUnresolved(String path, Object value, Set<String> unresolved) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> collectUnresolved(path + "." + key, nested, unresolved));
        } else if (value instanceof Iterable<?> list) {
            int i = 0;
            for (Object nested : list) {
                collectUnresolved(path + "[" + i++ + "]", nested, unresolved);
            }
        } else if (value instanceof String text && text.contains("${")) {
            unresolved.add(path); // not the value: it may hold a secret
        }
    }
}
