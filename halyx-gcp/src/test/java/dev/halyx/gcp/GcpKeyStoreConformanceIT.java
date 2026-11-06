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

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKey.CryptoKeyPurpose;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionState;
import com.google.cloud.kms.v1.CryptoKeyVersionTemplate;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import com.google.cloud.kms.v1.KeyRing;
import com.google.protobuf.FieldMask;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKeys;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.tck.KeyStoreConformanceTest;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key store kit on Cloud KMS emulated by Floci-gcp. Keys are created, rotated and disabled with the Cloud KMS
 * client. Floci-gcp lacks RSA-PSS, EC P-384 and MAC keys, and decrypts with a disabled version, so those cases are
 * covered by the memory provider and the real-cloud run instead.
 */
@Testcontainers(disabledWithoutDocker = true)
class GcpKeyStoreConformanceIT extends KeyStoreConformanceTest {

    private static final String PROJECT = "floci-local";
    private static final String KEY_RING = "projects/" + PROJECT + "/locations/global/keyRings/tck";
    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(4588)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;
    private static KeyManagementServiceClient admin;

    @BeforeAll
    static void start() throws Exception {
        var channel = ManagedChannelBuilder.forAddress(FLOCI.getHost(), FLOCI.getMappedPort(4588)).usePlaintext()
                .build();
        admin = KeyManagementServiceClient.create(KeyManagementServiceSettings.newBuilder()
                .setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                .setCredentialsProvider(NoCredentialsProvider.create()).build());
        admin.createKeyRing("projects/" + PROJECT + "/locations/global", "tck", KeyRing.getDefaultInstance());
        for (String name : new String[] {"tck-data", "tck-other", "tck-disposable"}) {
            create(name, CryptoKeyPurpose.ENCRYPT_DECRYPT, CryptoKeyVersionAlgorithm.GOOGLE_SYMMETRIC_ENCRYPTION);
        }
        create("tck-rsa", CryptoKeyPurpose.ASYMMETRIC_SIGN, CryptoKeyVersionAlgorithm.RSA_SIGN_PKCS1_2048_SHA256);
        create("tck-p256", CryptoKeyPurpose.ASYMMETRIC_SIGN, CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256);
        halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp")
                .endpoint(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4588)))
                .credentials(Credentials.anonymous())
                .property(GcpProperties.PROJECT_ID, PROJECT)).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
        if (admin != null) {
            admin.close();
        }
    }

    private static void create(String id, CryptoKeyPurpose purpose, CryptoKeyVersionAlgorithm algorithm) {
        admin.createCryptoKey(KEY_RING, id, CryptoKey.newBuilder().setPurpose(purpose)
                .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder().setAlgorithm(algorithm)).build());
    }

    @Override
    protected CloudContext context() {
        return halyx.provider("gcp");
    }

    @Override
    protected KeyStoreConfig.Builder config() {
        return KeyStoreConfig.builder().location(KEY_RING);
    }

    @Override
    protected String encryptionKey() {
        return "tck-data";
    }

    @Override
    protected String otherEncryptionKey() {
        return "tck-other";
    }

    @Override
    protected String disposableKey() {
        return "tck-disposable";
    }

    /** Floci-gcp makes no RSA-PSS or EC P-384 keys; Cloud KMS has both, and the real-cloud run signs with them. */
    @Override
    protected Set<SigningAlgorithm> declaredAlgorithms() {
        return EnumSet.of(SigningAlgorithm.RSA_PKCS1_SHA256, SigningAlgorithm.ECDSA_P256_SHA256);
    }

    @Override
    protected String signingKey(SigningAlgorithm algorithm) {
        return switch (algorithm) {
            case RSA_PKCS1_SHA256 -> "tck-rsa";
            case ECDSA_P256_SHA256 -> "tck-p256";
            default -> throw new UnsupportedOperationException("No " + algorithm + " key on Floci-gcp");
        };
    }

    /** The store declares MAC, as Cloud KMS has it; Floci-gcp can't make the key, so its cases are gaps below. */
    @Override
    protected Optional<String> macKey() {
        return Optional.of("tck-mac");
    }

    /** A new version; on a symmetric key, made primary too, as only the primary encrypts. */
    @Override
    protected void rotate(String name) {
        String key = KEY_RING + "/cryptoKeys/" + name;
        CryptoKeyVersion created = admin.createCryptoKeyVersion(key, CryptoKeyVersion.getDefaultInstance());
        if (admin.getCryptoKey(key).getPurpose() == CryptoKeyPurpose.ENCRYPT_DECRYPT) {
            admin.updateCryptoKeyPrimaryVersion(key, created.getName().substring(created.getName().lastIndexOf('/') + 1));
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

    private static void state(String name, CryptoKeyVersionState state) {
        for (CryptoKeyVersion version : admin.listCryptoKeyVersions(KEY_RING + "/cryptoKeys/" + name).iterateAll()) {
            admin.updateCryptoKeyVersion(version.toBuilder().setState(state).build(),
                    FieldMask.newBuilder().addPaths("state").build());
        }
    }

    @Override
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of(
                "aDisabledKeyRefusesDecryption", "Floci-gcp decrypts with a disabled version; Cloud KMS refuses with "
                        + "FAILED_PRECONDITION, covered by the memory provider and the real-cloud run",
                "macsAreMadeAndVerifiedByTheKeyService", "Floci-gcp has no MAC keys (\"Unsupported purpose: MAC\")",
                "theAsyncViewMacs", "Floci-gcp has no MAC keys (\"Unsupported purpose: MAC\")");
    }

    // Google Cloud's own cases

    @Test
    void sealedDataNamesTheCryptoKeyAndThePrimaryVersion() {
        SealedData sealed = store().key(encryptionKey()).encrypt(DATA, AAD);
        String primary = admin.getCryptoKey(KEY_RING + "/cryptoKeys/" + encryptionKey()).getPrimary().getName();

        assertThat(sealed.keys()).singleElement().satisfies(entry -> {
            assertThat(entry.keyId()).isEqualTo("gcp-kms:" + KEY_RING + "/cryptoKeys/" + encryptionKey());
            assertThat(entry.version()).contains(primary.substring(primary.lastIndexOf('/') + 1));
            assertThat(entry.algorithm()).contains(WrapAlgorithm.GCP_KMS);
        });
    }

    @Test
    void anotherKeyRingIsAnotherStore() {
        admin.createKeyRing("projects/" + PROJECT + "/locations/global", "tck-eu", KeyRing.getDefaultInstance());
        admin.createCryptoKey("projects/" + PROJECT + "/locations/global/keyRings/tck-eu", encryptionKey(),
                CryptoKey.newBuilder().setPurpose(CryptoKeyPurpose.ENCRYPT_DECRYPT).build());
        KeyStore eu = context().keyStore(KeyStoreConfig.builder()
                .location("projects/" + PROJECT + "/locations/global/keyRings/tck-eu").build());

        SealedData sealed = CryptoKeys.encrypt(DATA, AAD, eu.key(encryptionKey()));

        assertThat(eu.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThatThrownBy(() -> store().decrypt(sealed, AAD)).isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void nativeNamesTakeFullNamesOnlyInTheKeyRing() {
        KeyStore nativeNames = context().keyStore(config().nativeNames(true).build());

        assertThat(nativeNames.key(encryptionKey()).decrypt(store().key(encryptionKey()).encrypt(DATA, AAD), AAD))
                .as("a bare id is a key of this ring").isEqualTo(DATA);
        assertThatThrownBy(() -> nativeNames.key("projects/" + PROJECT + "/locations/global/keyRings/other/cryptoKeys/"
                + encryptionKey()).metadata()).isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void aStoreNeedsItsKeyRing() {
        assertThatThrownBy(() -> context().keyStore(KeyStoreConfig.defaults()).key(encryptionKey()).metadata())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("key ring");
        assertThatThrownBy(() -> context().keyStore(KeyStoreConfig.builder().location("tck").build())
                .key(encryptionKey()).metadata())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("projects/");
    }
}
