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
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The document encryption kit on the memory provider, with memory keys. */
class InMemoryDocumentEncryptionConformanceTest extends DocumentEncryptionConformanceTest {

    private static Halyx halyx;
    private static KeyStore keys;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
        keys = halyx.provider("test").keyStore();
        for (String name : new String[] {"fields", "tenant-a", "tenant-b"}) {
            keys.unwrap(InMemoryKeys.class).create(name, KeyPurpose.ENCRYPTION);
        }
    }

    @AfterAll
    static void stop() {
        halyx.close();
    }

    @Override
    protected TableConfig.Builder table(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return DocumentStoreConformanceTest.indexes(table, sorted);
    }

    @Override
    protected DocumentStore store(TableConfig table) {
        return halyx.provider("test").documentStore(table);
    }

    @Override
    protected KeyStore keyStore() {
        return keys;
    }
}
