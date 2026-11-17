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

import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.models.KeyProperties;
import com.azure.storage.blob.BlobContainerClient;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.azure.blob.AzureBlobStoreDriver;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyOptions;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.tck.BlobEncryptionConformanceTest;
import dev.halyx.tck.BlobServerSideKeyConformanceTest;
import dev.halyx.tck.BlobStoreConformanceTest;
import dev.halyx.tck.DocumentEncryptionConformanceTest;
import dev.halyx.tck.DocumentStoreConformanceTest;
import dev.halyx.tck.KeyStoreConformanceTest;
import dev.halyx.tck.ParameterEncryptionConformanceTest;
import dev.halyx.tck.ParameterStoreConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Key management on real Azure (tier 1): the key store kit on Key Vault, and every service that uses keys, through
 * client-side views and, for Blob Storage, an encryption scope on a customer-managed key. Runs only with
 * {@code -Dhalyx.real.azure.keys=true}, normally through cloud-testing/scripts/azure-real-cloud.sh.
 *
 * <p>The keys are the long-lived ones in cloud-testing/infra/azure/keys ({@code halyx.real.azure.keys-vault-url}),
 * named {@code tck-*}; the store has the prefix {@code tck-}, so the kits' own names find them. The storage account,
 * Cosmos DB account, secrets vault and App Configuration store are tier 1's, passed in as for {@link RealAzureIT}.
 * Tier 1 makes the encryption scope ({@code halyx.real.azure.encryption-scope}) when it's given the keys stack's
 * outputs; without it, the server-side key case doesn't run. Key Vault versions cost nothing a month, so the kit
 * rotates on every run.
 */
@EnabledIfSystemProperty(named = "halyx.real.azure.keys", matches = "true")
class RealAzureKeysIT {

    private static final String STORAGE_ACCOUNT = System.getProperty("halyx.real.azure.storage-account");
    private static final String CONTAINER = System.getProperty("halyx.real.azure.container");
    private static final String COSMOS = System.getProperty("halyx.real.azure.cosmos-endpoint");
    private static final String KEY_VAULT = System.getProperty("halyx.real.azure.key-vault-url");
    private static final String APP_CONFIG = System.getProperty("halyx.real.azure.appconfig-endpoint");
    private static final String KEYS_VAULT = System.getProperty("halyx.real.azure.keys-vault-url");

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")
                .property(AzureProperties.STORAGE_ACCOUNT, STORAGE_ACCOUNT)
                .property(AzureProperties.COSMOS_ENDPOINT, COSMOS)
                .property(AzureProperties.COSMOS_DATABASE, "halyx")).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    private static CloudContext azure() {
        return halyx.provider("azure");
    }

    private static KeyStoreConfig.Builder keyConfig() {
        return KeyStoreConfig.builder().location(KEYS_VAULT).prefix("tck-");
    }

    /** The long-lived keys, by the kits' names. */
    private static KeyStore keys() {
        return azure().keyStore(keyConfig().build());
    }

    private static TableConfig.Builder table(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return DocumentStoreConformanceTest.indexes(table, sorted);
    }

    // The key store

    @Nested
    class KeyVaultKeys extends KeyStoreConformanceTest {

        private KeyClient admin() {
            return store().unwrap(KeyClient.class);
        }

        @Override
        protected CloudContext context() {
            return azure();
        }

        @Override
        protected KeyStoreConfig.Builder config() {
            return keyConfig();
        }

        @Override
        protected String encryptionKey() {
            return "data";
        }

        @Override
        protected String otherEncryptionKey() {
            return "other";
        }

        @Override
        protected String disposableKey() {
            return "disposable";
        }

        @Override
        protected String signingKey(SigningAlgorithm algorithm) {
            return switch (algorithm) {
                case RSA_PSS_SHA256, RSA_PKCS1_SHA256 -> "rsa";
                case ECDSA_P256_SHA256 -> "p256";
                case ECDSA_P384_SHA384 -> "p384";
            };
        }

        @Override
        protected void rotate(String name) {
            admin().rotateKey("tck-" + name);
        }

        /** Key Vault disables versions one by one: every version, so no older one stays usable. */
        @Override
        protected void disable(String name) {
            enabled(name, false);
        }

        @Override
        protected void enable(String name) {
            enabled(name, true);
        }

        private void enabled(String name, boolean enabled) {
            List<KeyProperties> versions = new ArrayList<>();
            admin().listPropertiesOfKeyVersions("tck-" + name).forEach(versions::add);
            versions.forEach(version -> admin().updateKeyProperties(version.setEnabled(enabled)));
        }

        @Override
        protected Duration keyStateLag() {
            return Duration.ofSeconds(30);
        }
    }

    // Blobs

    /** The whole blob kit through a view sealed with a Key Vault key. */
    @Nested
    class EncryptedBlob extends BlobStoreConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return azure().blobStore(CONTAINER);
        }

        @Override
        protected BlobStore wrap(BlobStore store) {
            return store.encryptedWith(keys().key("blobs"));
        }

        @Override
        protected Map<String, String> notApplicableWithEncryption() {
            return Map.of("listSummariesCarrySize", "a listing reports the stored, sealed size of an encrypted blob (D22)");
        }
    }

    @Nested
    class BlobEncryption extends BlobEncryptionConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return azure().blobStore(CONTAINER);
        }

        @Override
        protected KeyStore keys() {
            return azure().keyStore(keyConfig().segmentSize(KeyStoreConfig.MIN_SEGMENT_SIZE).build());
        }
    }

    @Nested
    @EnabledIfSystemProperty(named = "halyx.real.azure.encryption-scope", matches = ".+")
    class BlobServerSideKey extends BlobServerSideKeyConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return azure().blobStore(CONTAINER);
        }

        /** tck-server-side, opened with the run's encryption scope, which Azure Storage encrypts with. */
        @Override
        protected CryptoKey serverSideKey() {
            return keys().key("server-side", KeyOptions.builder().property(AzureBlobStoreDriver.ENCRYPTION_SCOPE,
                    System.getProperty("halyx.real.azure.encryption-scope")).build());
        }

        @Override
        protected String expectedServerSideKey() {
            return System.getProperty("halyx.real.azure.encryption-scope");
        }

        /** The blob's encryption scope: real Blob Storage records it, unlike Floci-az. */
        @Override
        protected Optional<String> storedServerSideKey(BlobKey key) {
            return Optional.ofNullable(blobStore().unwrap(BlobContainerClient.class).getBlobClient(key.value())
                    .getProperties().getEncryptionScope());
        }
    }

    // Documents

    /** The whole document kit with the kit's non-key fields sealed by a Key Vault key. */
    @Nested
    class EncryptedCosmos extends DocumentStoreConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
            if (sorted) {
                table.sortKey("sk");
            }
            return azure().documentStore(tables(indexes(table, sorted)).build());
        }

        @Override
        protected TableConfig.Builder tables(TableConfig.Builder table) {
            return encrypted(table, keys().key("fields"));
        }
    }

    @Nested
    class CosmosEncryption extends DocumentEncryptionConformanceTest {
        @Override
        protected TableConfig.Builder table(boolean sorted) {
            return RealAzureKeysIT.table(sorted);
        }

        @Override
        protected DocumentStore store(TableConfig table) {
            return azure().documentStore(table);
        }

        @Override
        protected KeyStore keyStore() {
            return keys();
        }
    }

    // Parameters and secrets

    /** The whole parameter kit on the run's Key Vault secrets, through a view sealed with a Key Vault key. */
    @Nested
    class EncryptedKeyVaultSecrets extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected boolean nonBlockingAsyncDelete() {
            return false; // Key Vault starts deletes through a poller
        }

        @Override
        protected CloudContext context() {
            return azure();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets().location(KEY_VAULT);
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "-";
        }

        @Override
        protected String nativeName() {
            return "db--password";
        }

        @Override
        protected Duration deleteLag() {
            return Duration.ofSeconds(60);
        }

        @Override
        protected ParameterStore wrap(ParameterStore store) {
            return store.encryptedWith(keys().key("parameters"));
        }
    }

    @Nested
    class EncryptedAppConfiguration extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return azure();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().location(APP_CONFIG);
        }

        @Override
        protected ParameterStore wrap(ParameterStore store) {
            return store.encryptedWith(keys().key("parameters"));
        }
    }

    @Nested
    class KeyVaultSecretsEncryption extends ParameterEncryptionConformanceTest {
        @Override
        protected CloudContext context() {
            return azure();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets().location(KEY_VAULT);
        }

        @Override
        protected KeyStore keys() {
            return RealAzureKeysIT.keys();
        }

        @Override
        protected OptionalInt maxValueBytes() {
            return OptionalInt.of(25 * 1024);
        }
    }

    @Nested
    class AppConfigurationEncryption extends ParameterEncryptionConformanceTest {
        @Override
        protected CloudContext context() {
            return azure();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().location(APP_CONFIG);
        }

        @Override
        protected KeyStore keys() {
            return RealAzureKeysIT.keys();
        }

        @Override
        protected OptionalInt maxValueBytes() {
            return OptionalInt.of(10 * 1024);
        }
    }
}
