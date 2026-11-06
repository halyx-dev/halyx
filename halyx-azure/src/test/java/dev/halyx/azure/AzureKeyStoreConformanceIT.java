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
import com.azure.security.keyvault.keys.models.CreateEcKeyOptions;
import com.azure.security.keyvault.keys.models.CreateRsaKeyOptions;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyOperation;
import com.azure.security.keyvault.keys.models.KeyProperties;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.azure.parameters.AzureParameterProperties;
import dev.halyx.azure.parameters.ForceHttpPolicy;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyOptions;
import dev.halyx.keys.KeyPurpose;
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
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key store kit on Key Vault keys emulated by Floci-az. Keys are created, rotated and disabled with the Key Vault
 * SDK. There is no MAC key: Key Vault makes MACs only on Managed HSM, which Halyx doesn't support yet.
 */
@Testcontainers(disabledWithoutDocker = true)
class AzureKeyStoreConformanceIT extends KeyStoreConformanceTest {

    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);
    private static final KeyOperation[] WRAP = {KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY};
    private static final KeyOperation[] SIGN = {KeyOperation.SIGN, KeyOperation.VERIFY};

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.azure.image", "floci/floci-az:0.14.0")))
            .withExposedPorts(4577)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;
    private static KeyClient admin;

    @BeforeAll
    static void start() {
        admin = new KeyClientBuilder().vaultUrl(vaultUrl())
                .credential(request -> Mono.just(new AccessToken("emulator", OffsetDateTime.now().plusHours(1))))
                .addPolicy(new ForceHttpPolicy()).disableChallengeResourceVerification().buildClient();
        for (String name : List.of("tck-data", "tck-other", "tck-disposable")) {
            admin.createRsaKey(new CreateRsaKeyOptions(name).setKeySize(2048).setKeyOperations(WRAP));
        }
        admin.createRsaKey(new CreateRsaKeyOptions("tck-rsa").setKeySize(2048).setKeyOperations(SIGN));
        admin.createEcKey(new CreateEcKeyOptions("tck-p256").setCurveName(KeyCurveName.P_256).setKeyOperations(SIGN));
        admin.createEcKey(new CreateEcKeyOptions("tck-p384").setCurveName(KeyCurveName.P_384).setKeyOperations(SIGN));
        halyx = Halyx.builder().register("azure", ProviderConfig.of("azure").credentials(Credentials.anonymous()))
                .build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    private static String vaultUrl() {
        // https is what the SDKs require; the emulator policy downgrades it to http on the wire.
        return "https://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4577) + "/devstoreaccount1-keyvault";
    }

    @Override
    protected CloudContext context() {
        return halyx.provider("azure");
    }

    @Override
    protected KeyStoreConfig.Builder config() {
        return KeyStoreConfig.builder().location(vaultUrl()).property(AzureParameterProperties.INSECURE_HTTP, "true");
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
    protected void rotate(String name) {
        admin.rotateKey(name);
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

    private static void enabled(String name, boolean enabled) {
        List<KeyProperties> versions = new ArrayList<>();
        admin.listPropertiesOfKeyVersions(name).forEach(versions::add);
        versions.forEach(version -> admin.updateKeyProperties(version.setEnabled(enabled)));
    }

    // Azure's own cases

    @Test
    void sealedDataNamesTheVaultKeyAndTheVersionThatWrappedIt() {
        CryptoKey key = store().key(encryptionKey());
        SealedData sealed = key.encrypt(DATA, AAD);
        String current = admin.getKey(encryptionKey()).getProperties().getVersion();

        assertThat(sealed.keys()).singleElement().satisfies(entry -> {
            assertThat(entry.keyId()).isEqualTo("azure-kv:" + vaultUrl() + "/keys/" + encryptionKey());
            assertThat(entry.version()).contains(current);
            assertThat(entry.algorithm()).contains(WrapAlgorithm.AZURE_RSA_OAEP_256);
            assertThat(entry.bytes()).hasSize(256); // RSA-2048
        });
        assertThat(key.metadata().nativeId()).isEqualTo(vaultUrl() + "/keys/" + encryptionKey());
    }

    @Test
    void disablingAnOlderVersionRefusesOnlyTheDataItWrapped() {
        KeyStore store = store();
        SealedData before = store.key(disposableKey()).encrypt(DATA, AAD);
        String old = before.keys().get(0).version().orElseThrow();
        rotate(disposableKey());
        SealedData after = freshKey(store, disposableKey(), KeyOptions.defaults()).encrypt(DATA, AAD);
        admin.updateKeyProperties(admin.getKey(disposableKey(), old).getProperties().setEnabled(false));
        try {
            assertThatThrownBy(() -> store.decrypt(before, AAD)).isInstanceOf(AccessDeniedException.class);
            assertThat(store.decrypt(after, AAD)).isEqualTo(DATA);
            assertThat(freshKey(store, disposableKey(), KeyOptions.defaults()).metadata().versions())
                    .doesNotContain(old).contains(after.keys().get(0).version().orElseThrow());
        } finally {
            enable(disposableKey());
        }
        assertThat(store.decrypt(before, AAD)).isEqualTo(DATA);
    }

    @Test
    void anRsaKeyForBothWrapsAndSignsAndThePurposeOptionPicksOne() {
        admin.createRsaKey(new CreateRsaKeyOptions("tck-both").setKeySize(2048));
        KeyStore store = store();

        assertThat(store.key("tck-both").metadata().purposes())
                .containsExactlyInAnyOrder(KeyPurpose.ENCRYPTION, KeyPurpose.SIGNING);
        CryptoKey wrapping = store.key("tck-both", KeyOptions.builder().purpose(KeyPurpose.ENCRYPTION).build());
        CryptoKey signing = store.key("tck-both", KeyOptions.builder().purpose(KeyPurpose.SIGNING)
                .signingAlgorithm(SigningAlgorithm.RSA_PSS_SHA256).build());
        assertThat(wrapping.decrypt(wrapping.encrypt(DATA, AAD), AAD)).isEqualTo(DATA);
        assertThat(signing.verify(DATA, signing.sign(DATA))).isTrue();
        assertThatThrownBy(() -> wrapping.sign(DATA)).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> signing.encrypt(DATA, AAD)).isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void aKeyInAnotherVaultIsRefusedWithNativeNames() {
        KeyStore nativeNames = context().keyStore(config().nativeNames(true).build());

        assertThat(nativeNames.key(encryptionKey()).decrypt(store().key(encryptionKey()).encrypt(DATA, AAD), AAD))
                .as("a bare name is a key of this vault").isEqualTo(DATA);
        assertThatThrownBy(() -> nativeNames.key("https://other.vault.azure.net/keys/" + encryptionKey()).metadata())
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void aStoreNeedsItsVault() {
        assertThatThrownBy(() -> context().keyStore(KeyStoreConfig.defaults()).key(encryptionKey()).metadata())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("vault URL");
    }
}
