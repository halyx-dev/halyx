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

import dev.halyx.Halyx;
import dev.halyx.interceptor.RetryInterceptor;
import dev.halyx.interceptor.RetryPolicy;
import dev.halyx.keys.CryptoKey;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.spring.autoconfigure.HalyxBlobStores;
import org.springframework.boot.context.config.ConfigData;
import org.springframework.boot.context.config.ConfigDataLoader;
import org.springframework.boot.context.config.ConfigDataLoaderContext;

import java.time.Duration;
import java.util.List;

/**
 * Loads a parameter store into the Spring {@code Environment} at startup: every value becomes a
 * property whose key is its name with {@code /} replaced by {@code .} ({@code db/pool/size} becomes
 * {@code db.pool.size}), and JSON object values also add their fields ({@code db.creds.password}).
 * A temporarily unavailable backend is retried with backoff. With a {@code reload-interval}, the
 * {@code HalyxConfigReloader} keeps the properties up to date afterwards. A store with an {@code encryption-key} is
 * read through an encrypted view, with the key from its {@code halyx.key-stores} entry, whose provider is registered
 * alongside the store's.
 */
public class HalyxConfigDataLoader implements ConfigDataLoader<HalyxConfigDataResource> {

    @Override
    public ConfigData load(ConfigDataLoaderContext context, HalyxConfigDataResource resource) {
        if (resource.isSwitchedOff()) {
            return ConfigData.EMPTY;
        }
        Halyx.Builder builder = Halyx.builder()
                .classLoader(HalyxConfigDataLoader.class.getClassLoader())
                .register(resource.providerName(), resource.providerConfig())
                .interceptor(new RetryInterceptor(new RetryPolicy(resource.startupAttempts(), Duration.ofSeconds(1),
                        Duration.ofSeconds(10), 2.0)));
        resource.key().filter(key -> !key.provider().equals(resource.providerName()))
                .ifPresent(key -> builder.register(key.provider(), key.providerConfig()));
        try (Halyx halyx = builder.build()) {
            ParameterStore store = halyx.provider(resource.providerName()).parameterStore(resource.storeConfig());
            if (resource.key().isPresent()) {
                store = encrypted(halyx, store, resource.key().get());
            }
            return new ConfigData(List.of(new HalyxPropertySource(resource, HalyxPropertySource.load(store, resource))));
        }
    }

    /** {@code store} opening values with {@code key}, from a key store on {@code halyx}. */
    static ParameterStore encrypted(Halyx halyx, ParameterStore store, HalyxImportKey key) {
        CryptoKey cryptoKey = halyx.provider(key.provider()).keyStore(key.storeConfig()).key(key.name(), key.options());
        return HalyxBlobStores.encrypted(store, cryptoKey, key.allowPlaintext());
    }
}
