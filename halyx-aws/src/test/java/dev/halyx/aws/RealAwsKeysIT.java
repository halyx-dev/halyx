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
import dev.halyx.aws.parameters.SsmParameterDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Key management on real AWS (tier 1): the key store kit on KMS, and every service that uses keys, with keys and
 * without the cloud knowing (client-side views) and with the cloud's own encryption (SSE-KMS, SecureString, Secrets
 * Manager). Runs only with {@code -Dhalyx.real.aws.keys=true}, normally through cloud-testing/scripts/aws-real-cloud.sh.
 *
 * <p>The keys are the long-lived ones in cloud-testing/infra/aws/keys, aliases {@code tck-*}; the store has the prefix
 * {@code tck-}, so the kits' own names find them and listing stays within them. The bucket and tables are tier 1's,
 * passed in as for {@link RealAwsIT}. On-demand rotations per KMS key are limited for the key's life, so the kit's
 * rotation case runs only with {@code -Dhalyx.real.keys.rotate=true}.
 */
@EnabledIfSystemProperty(named = "halyx.real.aws.keys", matches = "true")
class RealAwsKeysIT {

    private static final String BUCKET = System.getProperty("halyx.real.aws.bucket");
    private static final String ITEMS = System.getProperty("halyx.real.aws.items-table");
    private static final String ITEMS_SORTED = System.getProperty("halyx.real.aws.items-sorted-table");
    private static final boolean ROTATE = Boolean.getBoolean("halyx.real.keys.rotate");

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("aws", ProviderConfig.of("aws")
                .region(System.getenv().getOrDefault("AWS_REGION", "ap-southeast-2"))).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    private static CloudContext aws() {
        return halyx.provider("aws");
    }

    private static KeyStoreConfig.Builder keyConfig() {
        return KeyStoreConfig.builder().prefix("tck-");
    }

    /** The long-lived keys, by the kits' names. */
    private static KeyStore keys() {
        return aws().keyStore(keyConfig().build());
    }

    /** The blob encryption kit's store: the smallest segments, so its streams cross segment boundaries. */
    private static KeyStore blobKeys() {
        return aws().keyStore(keyConfig().segmentSize(KeyStoreConfig.MIN_SEGMENT_SIZE).build());
    }

    private static TableConfig.Builder table(boolean sorted) {
        TableConfig.Builder table = TableConfig.builder(sorted ? ITEMS_SORTED : ITEMS);
        if (sorted) {
            table.sortKey("sk");
        }
        return table;
    }

    // The key store

    @Nested
    class Kms extends KeyStoreConformanceTest {

        private KmsClient admin() {
            return store().unwrap(KmsClient.class);
        }

        /** RotateKeyOnDemand, DisableKey and EnableKey take a key ID or ARN, not an alias. */
        private String keyIdOf(String name) {
            return admin().describeKey(b -> b.keyId("alias/tck-" + name)).keyMetadata().keyId();
        }

        @Override
        protected CloudContext context() {
            return aws();
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
        protected Optional<String> macKey() {
            return Optional.of("mac");
        }

        @Override
        protected boolean rotatesSigningKeys() {
            return false;
        }

        @Override
        protected void rotate(String name) {
            admin().rotateKeyOnDemand(b -> b.keyId(keyIdOf(name)));
        }

        @Override
        protected void disable(String name) {
            admin().disableKey(b -> b.keyId(keyIdOf(name)));
        }

        @Override
        protected void enable(String name) {
            admin().enableKey(b -> b.keyId(keyIdOf(name)));
        }

        /** KMS documents key state changes as eventually consistent. */
        @Override
        protected Duration keyStateLag() {
            return Duration.ofMinutes(1);
        }

        @Override
        protected Map<String, String> knownEnvironmentGaps() {
            return ROTATE ? Map.of() : Map.of("rewrapAfterRotationChangesOnlyTheHeader",
                    "rotates tck-data, whose on-demand rotations are limited; run with -Dhalyx.real.keys.rotate=true");
        }
    }

    // Blobs

    /** The whole blob kit through a view sealed with a KMS key. */
    @Nested
    class EncryptedS3 extends BlobStoreConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return aws().blobStore(BUCKET);
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
    class S3Encryption extends BlobEncryptionConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return aws().blobStore(BUCKET);
        }

        @Override
        protected KeyStore keys() {
            return blobKeys();
        }
    }

    @Nested
    class S3ServerSideKey extends BlobServerSideKeyConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return aws().blobStore(BUCKET);
        }

        @Override
        protected CryptoKey serverSideKey() {
            return keys().key("server-side");
        }

        /** HeadObject's {@code SSEKMSKeyId}, when the object is SSE-KMS. */
        @Override
        protected Optional<String> storedServerSideKey(BlobKey key) {
            var head = blobStore().unwrap(S3Client.class).headObject(b -> b.bucket(BUCKET).key(key.value()));
            return head.serverSideEncryption() == ServerSideEncryption.AWS_KMS
                    ? Optional.ofNullable(head.ssekmsKeyId()) : Optional.empty();
        }
    }

    // Documents

    /** The whole document kit with the kit's non-key fields sealed by a KMS key. */
    @Nested
    class EncryptedDynamoDb extends DocumentStoreConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            return aws().documentStore(tables(indexes(table(sorted), sorted)).build());
        }

        @Override
        protected TableConfig.Builder tables(TableConfig.Builder table) {
            return encrypted(table, keys().key("fields"));
        }
    }

    @Nested
    class DynamoDbEncryption extends DocumentEncryptionConformanceTest {
        @Override
        protected TableConfig.Builder table(boolean sorted) {
            return DocumentStoreConformanceTest.indexes(RealAwsKeysIT.table(sorted), sorted);
        }

        @Override
        protected DocumentStore store(TableConfig table) {
            return aws().documentStore(table);
        }

        @Override
        protected KeyStore keyStore() {
            return keys();
        }
    }

    // Parameters and secrets

    /** The whole parameter kit through a view sealed with a KMS key. */
    @Nested
    class EncryptedSsm extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected String nativePrefix(String unique) {
            return "/" + unique + "/";
        }

        @Override
        protected ParameterStore wrap(ParameterStore store) {
            return store.encryptedWith(keys().key("parameters"));
        }

        @Override
        protected Map<String, String> notApplicableWithEncryption() {
            return Map.of("largeValues", "3,500 bytes seal to about 4.8 KB, over the 4 KB a standard SSM parameter holds");
        }
    }

    @Nested
    class EncryptedSecretsManager extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected ParameterStore wrap(ParameterStore store) {
            return store.encryptedWith(keys().key("parameters"));
        }

        /** AWS documents ListSecrets as eventually consistent. */
        @Override
        protected Duration listingLag() {
            return Duration.ofMinutes(2);
        }

        @Override
        protected Duration deleteLag() {
            return Duration.ofSeconds(30);
        }
    }

    @Nested
    class SsmEncryption extends ParameterEncryptionConformanceTest {
        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected KeyStore keys() {
            return RealAwsKeysIT.keys();
        }

        @Override
        protected OptionalInt maxValueBytes() {
            return OptionalInt.of(8 * 1024);
        }
    }

    @Nested
    class SecretsManagerEncryption extends ParameterEncryptionConformanceTest {
        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected KeyStore keys() {
            return RealAwsKeysIT.keys();
        }

        @Override
        protected OptionalInt maxValueBytes() {
            return OptionalInt.of(64 * 1024);
        }

        @Override
        protected Duration listingLag() {
            return Duration.ofMinutes(2);
        }
    }

    private static ParameterStoreConfig.Builder secureParameters() {
        return ParameterStoreConfig.parameters().property(SsmParameterDriver.SECURE, "true");
    }

    /** The whole parameter kit on SecureString parameters encrypted by tck-server-side. */
    @Nested
    class KeyedSsm extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return secureParameters().serverSideKey(keys().key("server-side"));
        }

        @Override
        protected String nativePrefix(String unique) {
            return "/" + unique + "/";
        }
    }

    @Nested
    class KeyedSecretsManager extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets().serverSideKey(keys().key("server-side"));
        }

        @Override
        protected Duration listingLag() {
            return Duration.ofMinutes(2);
        }

        @Override
        protected Duration deleteLag() {
            return Duration.ofSeconds(30);
        }
    }

    @Nested
    class SsmServerSideKey extends ParameterServerSideKeyConformanceTest {
        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return secureParameters();
        }

        @Override
        protected CryptoKey serverSideKey() {
            return keys().key("server-side");
        }

        /** DescribeParameters' {@code KeyId}: real SSM records it, unlike Floci. */
        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            return store.unwrap(SsmClient.class).describeParameters(b -> b.parameterFilters(
                            f -> f.key("Name").values("/" + name))).parameters().stream()
                    .findFirst().map(p -> p.keyId());
        }

        @Override
        protected boolean everyPutUsesTheKey() {
            return true;
        }
    }

    @Nested
    class SecretsManagerServerSideKey extends ParameterServerSideKeyConformanceTest {
        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected CryptoKey serverSideKey() {
            return keys().key("server-side");
        }

        /** DescribeSecret's {@code KmsKeyId}: none for the account's default key. */
        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            return Optional.ofNullable(store.unwrap(SecretsManagerClient.class)
                    .describeSecret(b -> b.secretId(name)).kmsKeyId());
        }
    }
}
