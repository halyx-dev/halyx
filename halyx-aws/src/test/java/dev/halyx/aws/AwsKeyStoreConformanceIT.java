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
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.tck.KeyStoreConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The key store kit on AWS KMS emulated by Floci. Keys are created, rotated and disabled with the KMS SDK. */
@Testcontainers(disabledWithoutDocker = true)
class AwsKeyStoreConformanceIT extends KeyStoreConformanceTest {

    private static final String REGION = "us-east-1";
    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;
    private static KmsClient admin;

    @BeforeAll
    static void start() {
        admin = KmsClient.builder().region(Region.of(REGION)).endpointOverride(endpoint())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
        Map.of("tck-data", KeySpec.SYMMETRIC_DEFAULT, "tck-other", KeySpec.SYMMETRIC_DEFAULT,
                        "tck-disposable", KeySpec.SYMMETRIC_DEFAULT, "tck-rsa", KeySpec.RSA_2048,
                        "tck-p256", KeySpec.ECC_NIST_P256, "tck-p384", KeySpec.ECC_NIST_P384, "tck-mac", KeySpec.HMAC_256)
                .forEach(AwsKeyStoreConformanceIT::create);
        halyx = Halyx.builder().register("aws", ProviderConfig.of("aws").region(REGION).endpoint(endpoint())
                .credentials(Credentials.of("test", "test"))).build();
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

    private static URI endpoint() {
        return URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
    }

    private static void create(String name, KeySpec spec) {
        KeyUsageType usage = switch (spec) {
            case SYMMETRIC_DEFAULT -> KeyUsageType.ENCRYPT_DECRYPT;
            case HMAC_256 -> KeyUsageType.GENERATE_VERIFY_MAC;
            default -> KeyUsageType.SIGN_VERIFY;
        };
        String keyId = admin.createKey(b -> b.keySpec(spec).keyUsage(usage)).keyMetadata().keyId();
        admin.createAlias(b -> b.aliasName("alias/" + name).targetKeyId(keyId));
    }

    /** RotateKeyOnDemand, DisableKey and EnableKey take a key ID or ARN, not an alias. */
    private static String keyIdOf(String name) {
        return admin.describeKey(b -> b.keyId("alias/" + name)).keyMetadata().keyId();
    }

    @Override
    protected CloudContext context() {
        return halyx.provider("aws");
    }

    @Override
    protected KeyStoreConfig.Builder config() {
        return KeyStoreConfig.builder();
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

    @Override
    protected String signingKey(SigningAlgorithm algorithm) {
        return switch (algorithm) {
            case RSA_PSS_SHA256, RSA_PKCS1_SHA256 -> "tck-rsa";
            case ECDSA_P256_SHA256 -> "tck-p256";
            case ECDSA_P384_SHA384 -> "tck-p384";
        };
    }

    @Override
    protected Optional<String> macKey() {
        return Optional.of("tck-mac");
    }

    @Override
    protected boolean rotatesSigningKeys() {
        return false;
    }

    @Override
    protected void rotate(String name) {
        admin.rotateKeyOnDemand(b -> b.keyId(keyIdOf(name)));
    }

    @Override
    protected void disable(String name) {
        admin.disableKey(b -> b.keyId(keyIdOf(name)));
    }

    @Override
    protected void enable(String name) {
        admin.enableKey(b -> b.keyId(keyIdOf(name)));
    }

    // AWS's own cases

    @Test
    void sealedDataNamesTheKeyArnWithoutAVersion() {
        SealedData sealed = store().key(encryptionKey()).encrypt(DATA, AAD);
        String arn = admin.describeKey(b -> b.keyId("alias/" + encryptionKey())).keyMetadata().arn();

        assertThat(sealed.keys()).singleElement().satisfies(entry -> {
            assertThat(entry.keyId()).isEqualTo("aws-kms:" + arn);
            assertThat(entry.version()).isEmpty();
            assertThat(entry.algorithm()).contains(WrapAlgorithm.AWS_KMS);
        });
    }

    @Test
    void awsManagedAliasesAreNeverListed() {
        assertThat(store().keys("")).extracting(KeyMetadata::name).isNotEmpty()
                .noneMatch(name -> name.startsWith("aws/"));
    }

    @Test
    void nativeNamesTakeAliasesAndKeyIds() {
        KeyStore nativeNames = context().keyStore(config().nativeNames(true).build());
        String keyId = keyIdOf(encryptionKey());

        SealedData byAlias = nativeNames.key("alias/" + encryptionKey()).encrypt(DATA, AAD);
        SealedData byKeyId = nativeNames.key(keyId).encrypt(DATA, AAD);

        assertThat(byAlias.keys().get(0).keyId()).isEqualTo(byKeyId.keys().get(0).keyId())
                .isEqualTo(store().key(encryptionKey()).metadata().keyId());
        assertThat(store().decrypt(byKeyId, AAD)).isEqualTo(DATA);
        assertThat(nativeNames.keys("alias/tck-")).extracting(KeyMetadata::name).contains("alias/" + encryptionKey());
    }

    @Test
    void aStoreHasNoLocation() {
        assertThatThrownBy(() -> context().keyStore(KeyStoreConfig.builder().location("eu-west-2").build())
                .key(encryptionKey()).metadata())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no location");
    }
}
