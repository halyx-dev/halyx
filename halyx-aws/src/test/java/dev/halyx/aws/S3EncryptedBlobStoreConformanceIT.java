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
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import dev.halyx.tck.BlobEncryptionConformanceTest;
import dev.halyx.tck.BlobServerSideKeyConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.util.Map;
import java.util.Optional;

/**
 * S3 on Floci, encrypted: the whole blob kit through a view sealed with a memory key, then the encryption kit with a
 * memory key and with keys on Floci's KMS, and SSE-KMS with a key on Floci's KMS.
 */
class S3EncryptedBlobStoreConformanceIT extends S3BlobStoreConformanceIT {

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
            return S3EncryptedBlobStoreConformanceIT.this.blobStore();
        }
    }

    @Nested
    class EncryptionOnKms extends BlobEncryptionConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return S3EncryptedBlobStoreConformanceIT.this.blobStore();
        }

        /** Keys on Floci's KMS, made once: aliases alias/blobs, alias/tenant-a and alias/tenant-b. */
        @Override
        protected KeyStore keys() {
            synchronized (S3EncryptedBlobStoreConformanceIT.class) {
                if (kms == null) {
                    KmsClient admin = halyx.provider("aws").keyStore().unwrap(KmsClient.class);
                    for (String name : new String[] {"blobs", "tenant-a", "tenant-b"}) {
                        String id = admin.createKey(b -> b.keySpec(KeySpec.SYMMETRIC_DEFAULT)
                                .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)).keyMetadata().keyId();
                        admin.createAlias(b -> b.aliasName("alias/" + name).targetKeyId(id));
                    }
                    kms = halyx.provider("aws").keyStore(KeyStoreConfig.builder()
                            .segmentSize(KeyStoreConfig.MIN_SEGMENT_SIZE).build());
                }
                return kms;
            }
        }
    }

    @Nested
    class ServerSideKey extends BlobServerSideKeyConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return S3EncryptedBlobStoreConformanceIT.this.blobStore();
        }

        /** A key on Floci's KMS, made once, alias/storage. */
        @Override
        protected CryptoKey serverSideKey() {
            synchronized (S3EncryptedBlobStoreConformanceIT.class) {
                if (serverSide == null) {
                    KeyStore keys = halyx.provider("aws").keyStore();
                    KmsClient admin = keys.unwrap(KmsClient.class);
                    String id = admin.createKey(b -> b.keySpec(KeySpec.SYMMETRIC_DEFAULT)
                            .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)).keyMetadata().keyId();
                    admin.createAlias(b -> b.aliasName("alias/storage").targetKeyId(id));
                    serverSide = keys.key("storage");
                }
                return serverSide;
            }
        }

        /** HeadObject's {@code SSEKMSKeyId}, when the object is SSE-KMS. */
        @Override
        protected Optional<String> storedServerSideKey(BlobKey key) {
            var head = blobStore().unwrap(S3Client.class).headObject(b -> b.bucket(blobStore().bucket()).key(key.value()));
            return head.serverSideEncryption() == ServerSideEncryption.AWS_KMS
                    ? Optional.ofNullable(head.ssekmsKeyId()) : Optional.empty();
        }
    }
}
