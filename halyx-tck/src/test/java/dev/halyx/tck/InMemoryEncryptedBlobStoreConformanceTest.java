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

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.storage.BlobStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.Map;

/** The whole blob kit again, through a store encrypted with a memory key. */
class InMemoryEncryptedBlobStoreConformanceTest extends InMemoryBlobStoreConformanceTest {

    private static Halyx keys;
    private static CryptoKey key;

    @BeforeAll
    static void key() {
        keys = Halyx.builder().register("keys", ProviderConfig.of("memory")).build();
        KeyStore store = keys.provider("keys").keyStore();
        store.unwrap(InMemoryKeys.class).create("blobs", KeyPurpose.ENCRYPTION);
        key = store.key("blobs");
    }

    @AfterAll
    static void closeKeys() {
        keys.close();
    }

    @Override
    protected BlobStore wrap(BlobStore store) {
        return store.encryptedWith(key);
    }

    @Override
    protected Map<String, String> notApplicableWithEncryption() {
        return Map.of("listSummariesCarrySize", "a listing reports the stored, sealed size of an encrypted blob (D22)");
    }
}
