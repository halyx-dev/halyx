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
package dev.halyx.tck;

import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.memory.InMemoryParameters;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;

import java.util.Optional;

/**
 * The server-side key kits on the in-memory provider, which records the key each parameter is created with: the key
 * kit, and the whole parameter store kit through keyed stores.
 */
class InMemoryParameterServerSideKeyConformanceTest {

    private static Halyx halyx;
    private static CryptoKey key;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
        KeyStore keys = halyx.provider("test").keyStore();
        keys.unwrap(InMemoryKeys.class).create("parameters", KeyPurpose.ENCRYPTION);
        key = keys.key("parameters");
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    @Nested
    class Secrets extends ParameterServerSideKeyConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("test");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected CryptoKey serverSideKey() {
            return key;
        }

        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            return store.unwrap(InMemoryParameters.class).serverSideKey(name);
        }
    }

    @Nested
    class KeyedParameterStore extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("test");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().serverSideKey(key);
        }
    }
}
