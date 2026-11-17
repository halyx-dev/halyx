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
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionState;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.parametermanager.v1.ParameterManagerClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretName;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.protobuf.FieldMask;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.CryptoKey;
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
import dev.halyx.tck.ParameterServerSideKeyConformanceTest;
import dev.halyx.tck.ParameterStoreConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Key management on real Google Cloud (tier 1): the key store kit on Cloud KMS, and every service that uses keys,
 * through client-side views and with the services' own customer-managed keys (Cloud Storage, Secret Manager, Parameter
 * Manager). Runs only with {@code -Dhalyx.real.gcp.keys=true}, normally through cloud-testing/scripts/gcp-real-cloud.sh.
 *
 * <p>The keys are the long-lived ones in cloud-testing/infra/gcp/keys: the global key ring
 * ({@code halyx.real.gcp.key-ring}) with every key, and the regional one ({@code halyx.real.gcp.regional-key-ring})
 * with tck-server-side for the run's regional bucket and regional parameters. The stores have the prefix {@code tck-},
 * so the kits' own names find the keys. Each key version is billed while it exists, so the kit's rotation cases run
 * only with {@code -Dhalyx.real.keys.rotate=true}, and the script destroys the versions they leave behind.
 */
@EnabledIfSystemProperty(named = "halyx.real.gcp.keys", matches = "true")
class RealGcpKeysIT {

    private static final String PROJECT = System.getProperty("halyx.real.gcp.project");
    private static final String REGION = System.getProperty("halyx.real.gcp.region", "australia-southeast1");
    private static final String BUCKET = System.getProperty("halyx.real.gcp.bucket");
    private static final String DATABASE = System.getProperty("halyx.real.gcp.firestore-database");
    private static final String KEY_RING = System.getProperty("halyx.real.gcp.key-ring");
    private static final String REGIONAL_KEY_RING = System.getProperty("halyx.real.gcp.regional-key-ring");
    private static final boolean ROTATE = Boolean.getBoolean("halyx.real.keys.rotate");

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp")
                .property(GcpProperties.PROJECT_ID, PROJECT)
                .property(GcpProperties.FIRESTORE_DATABASE, DATABASE)).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    private static CloudContext gcp() {
        return halyx.provider("gcp");
    }

    private static KeyStoreConfig.Builder keyConfig() {
        return KeyStoreConfig.builder().location(KEY_RING).prefix("tck-");
    }

    /** The long-lived keys on the global ring, by the kits' names. */
    private static KeyStore keys() {
        return gcp().keyStore(keyConfig().build());
    }

    /** tck-server-side in the region, for what lives there: the run's bucket and regional parameters. */
    private static CryptoKey regionalServerSideKey() {
        return gcp().keyStore(KeyStoreConfig.builder().location(REGIONAL_KEY_RING).prefix("tck-").build())
                .key("server-side");
    }

    private static TableConfig.Builder table(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
        if (sorted) {
            table.sortKey("sk");
        }
        return DocumentStoreConformanceTest.indexes(table, sorted);
    }

    /** Parameter Manager's own record of a parameter's key. */
    private static Optional<String> parameterKey(ParameterStore store, String location, String name) {
        String kmsKey = store.unwrap(ParameterManagerClient.class).getParameter("projects/" + PROJECT + "/locations/"
                + location + "/parameters/" + name.replace("/", "__")).getKmsKey();
        return kmsKey.isEmpty() ? Optional.empty() : Optional.of(kmsKey);
    }

    // The key store

    @Nested
    class CloudKms extends KeyStoreConformanceTest {

        private KeyManagementServiceClient admin() {
            return store().unwrap(KeyManagementServiceClient.class);
        }

        private String keyName(String name) {
            return KEY_RING + "/cryptoKeys/tck-" + name;
        }

        @Override
        protected CloudContext context() {
            return gcp();
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

        /** A Cloud KMS key has one algorithm, so the two RSA algorithms have a key each. */
        @Override
        protected String signingKey(SigningAlgorithm algorithm) {
            return switch (algorithm) {
                case RSA_PSS_SHA256 -> "rsa-pss";
                case RSA_PKCS1_SHA256 -> "rsa";
                case ECDSA_P256_SHA256 -> "p256";
                case ECDSA_P384_SHA384 -> "p384";
            };
        }

        @Override
        protected Optional<String> macKey() {
            return Optional.of("mac");
        }

        /** A new version; on a symmetric key, made primary too, as only the primary encrypts. */
        @Override
        protected void rotate(String name) {
            String key = keyName(name);
            CryptoKeyVersion created = admin().createCryptoKeyVersion(key, CryptoKeyVersion.getDefaultInstance());
            if (admin().getCryptoKey(key).getPurpose() == CryptoKeyPurpose.ENCRYPT_DECRYPT) {
                admin().updateCryptoKeyPrimaryVersion(key,
                        created.getName().substring(created.getName().lastIndexOf('/') + 1));
            }
        }

        @Override
        protected void disable(String name) {
            state(name, CryptoKeyVersionState.DISABLED);
        }

        @Override
        protected void enable(String name) {
            state(name, CryptoKeyVersionState.ENABLED);
        }

        /** Every version that isn't destroyed or scheduled for it. */
        private void state(String name, CryptoKeyVersionState state) {
            for (CryptoKeyVersion version : admin().listCryptoKeyVersions(keyName(name)).iterateAll()) {
                if (version.getState() == CryptoKeyVersionState.ENABLED
                        || version.getState() == CryptoKeyVersionState.DISABLED) {
                    admin().updateCryptoKeyVersion(version.toBuilder().setState(state).build(),
                            FieldMask.newBuilder().addPaths("state").build());
                }
            }
        }

        /** Cloud KMS documents key version state changes as eventually consistent. */
        @Override
        protected Duration keyStateLag() {
            return Duration.ofMinutes(5);
        }

        @Override
        protected Map<String, String> knownEnvironmentGaps() {
            String reason = "each new key version is billed while it exists; run with -Dhalyx.real.keys.rotate=true";
            return ROTATE ? Map.of() : Map.of("rewrapAfterRotationChangesOnlyTheHeader", reason,
                    "signaturesFromAPreviousVersionStillVerify", reason);
        }
    }

    // Blobs

    /** The whole blob kit through a view sealed with a Cloud KMS key. */
    @Nested
    class EncryptedCloudStorage extends BlobStoreConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return gcp().blobStore(BUCKET);
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
    class CloudStorageEncryption extends BlobEncryptionConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return gcp().blobStore(BUCKET);
        }

        @Override
        protected KeyStore keys() {
            return gcp().keyStore(keyConfig().segmentSize(KeyStoreConfig.MIN_SEGMENT_SIZE).build());
        }
    }

    @Nested
    class CloudStorageServerSideKey extends BlobServerSideKeyConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return gcp().blobStore(BUCKET);
        }

        @Override
        protected CryptoKey serverSideKey() {
            return regionalServerSideKey();
        }

        /** The object's {@code kmsKeyName}, without its version: real Cloud Storage records it, unlike Floci-gcp. */
        @Override
        protected Optional<String> storedServerSideKey(BlobKey key) {
            var blob = blobStore().unwrap(Storage.class).get(BlobId.of(BUCKET, key.value()));
            return Optional.ofNullable(blob.getKmsKeyName()).map(name -> name.replaceFirst("/cryptoKeyVersions/.*$", ""));
        }
    }

    // Documents

    /** The whole document kit with the kit's non-key fields sealed by a Cloud KMS key. */
    @Nested
    class EncryptedFirestore extends DocumentStoreConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
            if (sorted) {
                table.sortKey("sk");
            }
            return gcp().documentStore(tables(indexes(table, sorted)).build());
        }

        @Override
        protected TableConfig.Builder tables(TableConfig.Builder table) {
            return encrypted(table, keys().key("fields"));
        }
    }

    @Nested
    class FirestoreEncryption extends DocumentEncryptionConformanceTest {
        @Override
        protected TableConfig.Builder table(boolean sorted) {
            return RealGcpKeysIT.table(sorted);
        }

        @Override
        protected DocumentStore store(TableConfig table) {
            return gcp().documentStore(table);
        }

        @Override
        protected KeyStore keyStore() {
            return keys();
        }
    }

    // Secrets and parameters

    /** The whole parameter kit on Secret Manager, through a view sealed with a Cloud KMS key. */
    @Nested
    class EncryptedSecretManager extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected Duration overwriteLag() {
            return Duration.ofSeconds(30);
        }

        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }

        @Override
        protected ParameterStore wrap(ParameterStore store) {
            return store.encryptedWith(keys().key("parameters"));
        }
    }

    @Nested
    class EncryptedParameterManager extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }

        @Override
        protected ParameterStore wrap(ParameterStore store) {
            return store.encryptedWith(keys().key("parameters"));
        }
    }

    @Nested
    class SecretManagerEncryption extends ParameterEncryptionConformanceTest {
        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected KeyStore keys() {
            return RealGcpKeysIT.keys();
        }

        @Override
        protected OptionalInt maxValueBytes() {
            return OptionalInt.of(64 * 1024);
        }
    }

    @Nested
    class ParameterManagerEncryption extends ParameterEncryptionConformanceTest {
        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected KeyStore keys() {
            return RealGcpKeysIT.keys();
        }

        @Override
        protected OptionalInt maxValueBytes() {
            return OptionalInt.of(1024 * 1024);
        }
    }

    /** Secret Manager's automatic replication takes a key in a global key ring. */
    @Nested
    class SecretManagerServerSideKey extends ParameterServerSideKeyConformanceTest {
        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected CryptoKey serverSideKey() {
            return keys().key("server-side");
        }

        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            var replication = store.unwrap(SecretManagerServiceClient.class)
                    .getSecret(SecretName.of(PROJECT, name.replace("/", "__"))).getReplication();
            return replication.getAutomatic().hasCustomerManagedEncryption()
                    ? Optional.of(replication.getAutomatic().getCustomerManagedEncryption().getKmsKeyName())
                    : Optional.empty();
        }
    }

    @Nested
    class GlobalParameterManagerServerSideKey extends ParameterServerSideKeyConformanceTest {
        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected CryptoKey serverSideKey() {
            return keys().key("server-side");
        }

        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            return parameterKey(store, "global", name);
        }
    }

    @Nested
    class RegionalParameterManagerServerSideKey extends ParameterServerSideKeyConformanceTest {
        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().location(REGION);
        }

        @Override
        protected CryptoKey serverSideKey() {
            return regionalServerSideKey();
        }

        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            return parameterKey(store, REGION, name);
        }
    }
}
