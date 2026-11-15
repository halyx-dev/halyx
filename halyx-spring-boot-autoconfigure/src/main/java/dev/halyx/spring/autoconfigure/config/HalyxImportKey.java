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

import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.KeyOptions;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.parameters.EncryptedParameterStore;
import dev.halyx.parameters.ParameterStore;

/**
 * The key a {@code halyx:} import opens its store's values with: the store's {@code encryption-key}, resolved to its
 * {@code halyx.key-stores} entry and that entry's provider before the import loads.
 *
 * @param keyStoreName   the {@code halyx.key-stores} entry
 * @param provider       the key store's provider
 * @param providerConfig that provider's configuration
 * @param storeConfig    the key store's configuration
 * @param name           the key's name in the key store
 * @param options        the key's options
 * @param allowPlaintext whether values stored in the clear are read as they are
 */
record HalyxImportKey(String keyStoreName, String provider, ProviderConfig providerConfig, KeyStoreConfig storeConfig,
                      String name, KeyOptions options, boolean allowPlaintext) {

    /** {@code store} opening its values with this key from {@code keys}, the key store this one describes. */
    ParameterStore view(ParameterStore store, KeyStore keys) {
        EncryptedParameterStore view = store.encryptedWith(keys.key(name, options));
        return allowPlaintext ? view.allowPlaintext() : view;
    }
}
