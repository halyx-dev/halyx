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
package dev.halyx.azure;

import com.azure.core.credential.AccessToken;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.models.CreateRsaKeyOptions;
import com.azure.security.keyvault.keys.models.KeyOperation;
import dev.halyx.Halyx;
import dev.halyx.azure.parameters.AzureParameterProperties;
import dev.halyx.azure.parameters.ForceHttpPolicy;
import dev.halyx.config.Credentials;
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
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

/**
 * Cosmos DB on Floci-az, encrypted: the whole document kit (and the repository kits) with the kit's non-key fields
 * sealed by a memory key, then the encryption kit with memory keys and with RSA keys in Floci-az's Key Vault.
 */
class CosmosEncryptedDocumentStoreConformanceIT extends CosmosDocumentStoreConformanceIT {

    private static Halyx memory;
    private static KeyStore memoryKeys;
    private static Halyx vault;
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
        if (vault != null) {
            vault.close();
            vault = null;
        }
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
            return CosmosEncryptedDocumentStoreConformanceIT.this.table(sorted);
        }

        @Override
        protected DocumentStore store(TableConfig table) {
            return halyx.provider("azure").documentStore(table);
        }

        @Override
        protected KeyStore keyStore() {
            return memoryKeys;
        }
    }

    @Nested
    class EncryptionOnKeyVault extends DocumentEncryptionConformanceTest {
        @Override
        protected TableConfig.Builder table(boolean sorted) {
            return CosmosEncryptedDocumentStoreConformanceIT.this.table(sorted);
        }

        @Override
        protected DocumentStore store(TableConfig table) {
            return halyx.provider("azure").documentStore(table);
        }

        /** RSA keys fields, tenant-a and tenant-b in Floci-az's vault, made once; Key Vault logs in with Entra ID. */
        @Override
        protected KeyStore keyStore() {
            synchronized (CosmosEncryptedDocumentStoreConformanceIT.class) {
                if (kms == null) {
                    String vaultUrl = "https://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(HTTP_PORT)
                            + "/devstoreaccount1-keyvault";
                    KeyClient admin = new KeyClientBuilder().vaultUrl(vaultUrl)
                            .credential(request -> Mono.just(new AccessToken("emulator",
                                    OffsetDateTime.now().plusHours(1))))
                            .addPolicy(new ForceHttpPolicy()).disableChallengeResourceVerification().buildClient();
                    for (String name : new String[] {"fields", "tenant-a", "tenant-b"}) {
                        admin.createRsaKey(new CreateRsaKeyOptions(name).setKeySize(2048)
                                .setKeyOperations(KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY));
                    }
                    vault = Halyx.builder().register("vault", ProviderConfig.of("azure")
                            .credentials(Credentials.anonymous())).build();
                    kms = vault.provider("vault").keyStore(KeyStoreConfig.builder().location(vaultUrl)
                            .property(AzureParameterProperties.INSECURE_HTTP, "true").build());
                }
                return kms;
            }
        }
    }
}
