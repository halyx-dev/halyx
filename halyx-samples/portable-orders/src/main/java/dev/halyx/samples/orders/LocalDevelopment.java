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
package dev.halyx.samples.orders;

import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.parameters.ParameterStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The {@code local} profile keeps everything in memory, so nothing has stored a signing key; this stores a development
 * one at startup. On a cloud, the key is a secret created with the infrastructure. With the {@code keys} feature, it
 * also creates the {@code customer-data} key in the memory key store, as the infrastructure creates it in the cloud's
 * key service.
 */
@Component
@Profile("local")
class LocalDevelopment implements ApplicationRunner {

    private final ParameterStore secrets;
    private final String signingKey;
    private final ObjectProvider<CryptoKey> customerData;
    private final String keyPrefix;

    LocalDevelopment(@Qualifier("secrets") ParameterStore secrets,
                     @Value("${orders.local.signing-key:local-development-key}") String signingKey,
                     @Qualifier(OrderContacts.KEY) ObjectProvider<CryptoKey> customerData,
                     @Value("${halyx.key-stores.order-keys.prefix:}") String keyPrefix) {
        this.secrets = secrets;
        this.signingKey = signingKey;
        this.customerData = customerData;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (secrets.get(ReceiptService.SIGNING_KEY).isEmpty()) {
            secrets.put(ReceiptService.SIGNING_KEY, signingKey);
        }
        CryptoKey key = customerData.getIfAvailable();
        if (key != null) {
            // The store adds its prefix to key names; the memory store's keys are created under the full name.
            key.store().unwrap(InMemoryKeys.class).create(keyPrefix + key.name(), KeyPurpose.ENCRYPTION);
        }
    }
}
