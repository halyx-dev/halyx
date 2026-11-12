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
package dev.halyx.aws;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.tck.DocumentEncryptionConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;

/**
 * DynamoDB on Floci, encrypted: the whole document kit (and the repository kits) with the kit's non-key fields sealed
 * by a memory key, then the encryption kit with memory keys and with keys on Floci's KMS.
 */
class DynamoEncryptedDocumentStoreConformanceIT extends DynamoDocumentStoreConformanceIT {

    private static Halyx memory;
    private static KeyStore memoryKeys;
    private static KeyStore kms;

    @BeforeAll
    static void keys() {
        memory = Halyx.builder().register("keys", ProviderConfig.of("memory")).build();
        memoryKeys = memory.provider("keys").keyStore();
        for (String name : new String[] {"fields", "tenant-a", "tenant-b"}) {
            memoryKeys.unwrap(InMemoryKeys.class).create(name, KeyPurpose.ENCRYPTION);
        }
    }

    @AfterAll
    static void closeKeys() {
        memory.close();
        kms = null;
    }

    @Override
    protected TableConfig.Builder tables(TableConfig.Builder table) {
        return encrypted(table, memoryKeys.key("fields"));
    }

    private TableConfig.Builder table(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return indexes(table, sorted);
    }

    @Nested
    class Encryption extends DocumentEncryptionConformanceTest {
        @Override
        protected TableConfig.Builder table(boolean sorted) {
            return DynamoEncryptedDocumentStoreConformanceIT.this.table(sorted);
        }

        @Override
        protected DocumentStore store(TableConfig table) {
            return halyx.provider("aws").documentStore(table);
        }

        @Override
        protected KeyStore keyStore() {
            return memoryKeys;
        }
    }

    @Nested
    class EncryptionOnKms extends DocumentEncryptionConformanceTest {
        @Override
        protected TableConfig.Builder table(boolean sorted) {
            return DynamoEncryptedDocumentStoreConformanceIT.this.table(sorted);
        }

        @Override
        protected DocumentStore store(TableConfig table) {
            return halyx.provider("aws").documentStore(table);
        }

        /** Keys on Floci's KMS, made once: alias/docs-fields, alias/docs-tenant-a and alias/docs-tenant-b. */
        @Override
        protected KeyStore keyStore() {
            synchronized (DynamoEncryptedDocumentStoreConformanceIT.class) {
                if (kms == null) {
                    KmsClient admin = halyx.provider("aws").keyStore().unwrap(KmsClient.class);
                    for (String name : new String[] {"fields", "tenant-a", "tenant-b"}) {
                        String id = admin.createKey(b -> b.keySpec(KeySpec.SYMMETRIC_DEFAULT)
                                .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)).keyMetadata().keyId();
                        admin.createAlias(b -> b.aliasName("alias/docs-" + name).targetKeyId(id));
                    }
                    kms = halyx.provider("aws").keyStore(KeyStoreConfig.builder().prefix("docs-").build());
                }
                return kms;
            }
        }
    }
}
