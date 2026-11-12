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
package dev.halyx.gcp;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.nosql.TableConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * Google's Firestore emulator, encrypted: the whole document kit (and the repository kits) with the kit's non-key
 * fields sealed by a memory key. Covers the increment and transaction cases Floci-gcp skips.
 */
class FirestoreEmulatorEncryptedDocumentStoreIT extends FirestoreEmulatorDocumentStoreIT {

    private static Halyx memory;
    private static CryptoKey key;

    @BeforeAll
    static void keys() {
        memory = Halyx.builder().register("keys", ProviderConfig.of("memory")).build();
        KeyStore store = memory.provider("keys").keyStore();
        store.unwrap(InMemoryKeys.class).create("fields", KeyPurpose.ENCRYPTION);
        key = store.key("fields");
    }

    @AfterAll
    static void closeKeys() {
        memory.close();
    }

    @Override
    protected TableConfig.Builder tables(TableConfig.Builder table) {
        return encrypted(table, key);
    }
}
