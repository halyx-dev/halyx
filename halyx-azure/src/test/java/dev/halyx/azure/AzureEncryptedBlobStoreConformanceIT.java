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
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.azure.blob.AzureBlobStoreDriver;
import dev.halyx.keys.KeyOptions;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.tck.BlobEncryptionConformanceTest;
import dev.halyx.tck.BlobServerSideKeyConformanceTest;
import com.azure.storage.blob.BlobContainerClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Blob Storage on Floci-az, encrypted: the whole blob kit through a view sealed with a memory key, then the encryption
 * kit with a memory key and with keys in Floci-az's Key Vault.
 */
class AzureEncryptedBlobStoreConformanceIT extends AzureBlobStoreConformanceIT {

    /** Confirmed: the update fails with 501 on Floci-az, as the plain kit's metadata updates do. */
    private static final Map<String, String> SET_PROPERTIES_GAP = Map.of("updatingMetadataKeepsTheBlobReadable",
            "Floci-az 0.14.0 does not implement Set Blob Properties (501)");

    private static Halyx memory;
    private static CryptoKey key;
    private static Halyx vault;
    private static KeyStore kms;
    private static Halyx scopeVault;
    private static CryptoKey serverSide;

    @BeforeAll
    static void keys() {
        memory = Halyx.builder().register("keys", ProviderConfig.of("memory")).build();
        KeyStore store = memory.provider("keys").keyStore();
        store.unwrap(InMemoryKeys.class).create("blobs", KeyPurpose.ENCRYPTION);
        key = store.key("blobs");
    }

    @AfterAll
    static void closeKeys() {
        memory.close();
        if (vault != null) {
            vault.close();
            vault = null;
        }
        kms = null;
        if (scopeVault != null) {
            scopeVault.close();
            scopeVault = null;
        }
        serverSide = null;
    }

    @Override
    protected BlobStore wrap(BlobStore store) {
        return store.encryptedWith(key);
    }

    @Override
    protected Map<String, String> notApplicableWithEncryption() {
        return Map.of("listSummariesCarrySize", "a listing reports the stored, sealed size of an encrypted blob (D22)");
    }

    @Nested
    class Encryption extends BlobEncryptionConformanceTest {

        @Override
        protected Map<String, String> knownEnvironmentGaps() {
            return SET_PROPERTIES_GAP;
        }
        @Override
        protected BlobStore blobStore() {
            return AzureEncryptedBlobStoreConformanceIT.this.blobStore();
        }
    }

    @Nested
    class EncryptionOnKeyVault extends BlobEncryptionConformanceTest {

        @Override
        protected Map<String, String> knownEnvironmentGaps() {
            return SET_PROPERTIES_GAP;
        }
        @Override
        protected BlobStore blobStore() {
            return AzureEncryptedBlobStoreConformanceIT.this.blobStore();
        }

        /** RSA keys blobs, tenant-a and tenant-b in Floci-az's vault, made once; Key Vault logs in with Entra ID. */
        @Override
        protected KeyStore keys() {
            synchronized (AzureEncryptedBlobStoreConformanceIT.class) {
                if (kms == null) {
                    String vaultUrl = "https://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4577)
                            + "/devstoreaccount1-keyvault";
                    KeyClient admin = new KeyClientBuilder().vaultUrl(vaultUrl)
                            .credential(request -> Mono.just(new AccessToken("emulator",
                                    OffsetDateTime.now().plusHours(1))))
                            .addPolicy(new ForceHttpPolicy()).disableChallengeResourceVerification().buildClient();
                    for (String name : new String[] {"blobs", "tenant-a", "tenant-b"}) {
                        admin.createRsaKey(new CreateRsaKeyOptions(name).setKeySize(2048)
                                .setKeyOperations(KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY));
                    }
                    vault = Halyx.builder().register("vault", ProviderConfig.of("azure")
                            .credentials(Credentials.anonymous())).build();
                    kms = vault.provider("vault").keyStore(KeyStoreConfig.builder().location(vaultUrl)
                            .property(AzureParameterProperties.INSECURE_HTTP, "true")
                            .segmentSize(KeyStoreConfig.MIN_SEGMENT_SIZE).build());
                }
                return kms;
            }
        }
    }

    @Nested
    class ServerSideKey extends BlobServerSideKeyConformanceTest {

        @Override
        protected Map<String, String> knownEnvironmentGaps() {
            return Map.of("metadataAndTierChangesKeepTheKey", "Floci-az 0.14.0 does not implement Set Blob Properties (501)");
        }

        @Override
        protected BlobStore blobStore() {
            return AzureEncryptedBlobStoreConformanceIT.this.blobStore();
        }

        /** RSA key storage in Floci-az's vault, made once, opened with the encryption scope tck-scope. */
        @Override
        protected CryptoKey serverSideKey() {
            synchronized (AzureEncryptedBlobStoreConformanceIT.class) {
                if (serverSide == null) {
                    String vaultUrl = "https://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4577)
                            + "/devstoreaccount1-keyvault";
                    KeyClient admin = new KeyClientBuilder().vaultUrl(vaultUrl)
                            .credential(request -> Mono.just(new AccessToken("emulator",
                                    OffsetDateTime.now().plusHours(1))))
                            .addPolicy(new ForceHttpPolicy()).disableChallengeResourceVerification().buildClient();
                    admin.createRsaKey(new CreateRsaKeyOptions("storage").setKeySize(2048)
                            .setKeyOperations(KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY));
                    scopeVault = Halyx.builder().register("vault", ProviderConfig.of("azure")
                            .credentials(Credentials.anonymous())).build();
                    KeyStore keys = scopeVault.provider("vault").keyStore(KeyStoreConfig.builder().location(vaultUrl)
                            .property(AzureParameterProperties.INSECURE_HTTP, "true").build());
                    serverSide = keys.key("storage", KeyOptions.builder()
                            .property(AzureBlobStoreDriver.ENCRYPTION_SCOPE, "tck-scope").build());
                }
                return serverSide;
            }
        }

        @Override
        protected String expectedServerSideKey() {
            return "tck-scope";
        }

        /** The blob's encryption scope. */
        @Override
        protected Optional<String> storedServerSideKey(BlobKey key) {
            return Optional.ofNullable(blobStore().unwrap(BlobContainerClient.class).getBlobClient(key.value())
                    .getProperties().getEncryptionScope());
        }

        /** A0: Floci-az accepts {@code x-ms-encryption-scope} but doesn't record it; the real-cloud tier checks it. */
        @Override
        protected boolean reportsServerSideKeys() {
            return false;
        }
    }
}
