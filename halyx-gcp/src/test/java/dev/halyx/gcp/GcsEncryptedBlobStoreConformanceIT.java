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

import com.google.cloud.kms.v1.CryptoKey.CryptoKeyPurpose;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyRing;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.memory.InMemoryKeys;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.tck.BlobEncryptionConformanceTest;
import dev.halyx.tck.BlobServerSideKeyConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;

import java.util.Map;
import java.util.Optional;

/**
 * Cloud Storage on Floci-gcp, encrypted: the whole blob kit through a view sealed with a memory key, then the
 * encryption kit with a memory key and with keys on Floci-gcp's Cloud KMS, and customer-managed keys on writes.
 */
class GcsEncryptedBlobStoreConformanceIT extends GcsBlobStoreConformanceIT {

    private static final String KEY_RING = "projects/floci-local/locations/global/keyRings/tck-blobs";

    private static Halyx memory;
    private static CryptoKey key;
    private static KeyStore kms;
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
        kms = null;
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
        protected BlobStore blobStore() {
            return GcsEncryptedBlobStoreConformanceIT.this.blobStore();
        }
    }

    @Nested
    class EncryptionOnCloudKms extends BlobEncryptionConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return GcsEncryptedBlobStoreConformanceIT.this.blobStore();
        }

        /** Crypto keys blobs, tenant-a and tenant-b in a key ring on Floci-gcp, made once. */
        @Override
        protected KeyStore keys() {
            synchronized (GcsEncryptedBlobStoreConformanceIT.class) {
                if (kms == null) {
                    kms = halyx.provider("gcp").keyStore(KeyStoreConfig.builder().location(KEY_RING)
                            .segmentSize(KeyStoreConfig.MIN_SEGMENT_SIZE).build());
                    KeyManagementServiceClient admin = kms.unwrap(KeyManagementServiceClient.class);
                    admin.createKeyRing("projects/floci-local/locations/global", "tck-blobs",
                            KeyRing.getDefaultInstance());
                    for (String name : new String[] {"blobs", "tenant-a", "tenant-b"}) {
                        admin.createCryptoKey(KEY_RING, name, com.google.cloud.kms.v1.CryptoKey.newBuilder()
                                .setPurpose(CryptoKeyPurpose.ENCRYPT_DECRYPT).build());
                    }
                }
                return kms;
            }
        }
    }

    @Nested
    class ServerSideKey extends BlobServerSideKeyConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return GcsEncryptedBlobStoreConformanceIT.this.blobStore();
        }

        /** Crypto key storage in its own key ring on Floci-gcp, made once. */
        @Override
        protected CryptoKey serverSideKey() {
            synchronized (GcsEncryptedBlobStoreConformanceIT.class) {
                if (serverSide == null) {
                    String ring = "projects/floci-local/locations/global/keyRings/tck-storage";
                    KeyStore keys = halyx.provider("gcp").keyStore(KeyStoreConfig.builder().location(ring).build());
                    KeyManagementServiceClient admin = keys.unwrap(KeyManagementServiceClient.class);
                    admin.createKeyRing("projects/floci-local/locations/global", "tck-storage",
                            KeyRing.getDefaultInstance());
                    admin.createCryptoKey(ring, "storage", com.google.cloud.kms.v1.CryptoKey.newBuilder()
                            .setPurpose(CryptoKeyPurpose.ENCRYPT_DECRYPT).build());
                    serverSide = keys.key("storage");
                }
                return serverSide;
            }
        }

        /** The object's {@code kmsKeyName}, without its version. */
        @Override
        protected Optional<String> storedServerSideKey(BlobKey key) {
            var blob = blobStore().unwrap(Storage.class).get(BlobId.of(blobStore().bucket(), key.value()));
            return Optional.ofNullable(blob.getKmsKeyName()).map(name -> name.replaceFirst("/cryptoKeyVersions/.*$", ""));
        }

        /** A0: Floci-gcp accepts {@code kmsKeyName} on uploads but doesn't record it; the real-cloud tier checks it. */
        @Override
        protected boolean reportsServerSideKeys() {
            return false;
        }
    }
}
