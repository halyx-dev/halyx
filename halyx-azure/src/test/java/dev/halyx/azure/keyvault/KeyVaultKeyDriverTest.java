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
package dev.halyx.azure.keyvault;

import com.azure.security.keyvault.keys.models.JsonWebKey;
import com.azure.security.keyvault.keys.models.KeyCurveName;
import com.azure.security.keyvault.keys.models.KeyOperation;
import com.azure.security.keyvault.keys.models.KeyProperties;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.keys.WrappedKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyVaultKeyDriverTest {

    private static final String VAULT = "https://acme.vault.azure.net";

    @Test
    void theStoreIsItsVault() {
        KeyVaultKeyDriver driver = driver("https://Acme.Vault.Azure.NET/", false);

        assertThat(driver.inLocation("azure-kv:https://acme.vault.azure.net/keys/orders")).isTrue();
        assertThat(driver.inLocation("azure-kv:https://ACME.vault.azure.net/keys/orders"))
                .as("the host in any case").isTrue();
        assertThat(driver.inLocation("azure-kv:https://other.vault.azure.net/keys/orders")).isFalse();
        assertThat(driver.inLocation("azure-kv:https://acme.vault.azure.net/keys/orders/v1"))
                .as("ids carry no version").isFalse();
        assertThat(driver.inLocation("azure-kv:https://acme.vault.azure.net/keys/")).isFalse();
        assertThat(driver.inLocation("aws-kms:https://acme.vault.azure.net/keys/orders")).isFalse();
        assertThat(driver.unwraps(WrapAlgorithm.AZURE_RSA_OAEP_256)).isTrue();
        assertThat(driver.unwraps(WrapAlgorithm.AZURE_A256KW)).as("Managed HSM, not yet").isFalse();
    }

    @Test
    void vaultUrlsAreWrittenOneWay() {
        assertThat(KeyVaultKeyDriver.normalize("HTTPS://Acme.Vault.Azure.Net/")).isEqualTo(VAULT);
        assertThat(KeyVaultKeyDriver.normalize("https://localhost:4577/devstoreaccount1-keyvault/"))
                .isEqualTo("https://localhost:4577/devstoreaccount1-keyvault");
    }

    @Test
    void nativeNamesAlsoTakeTheVersionlessKeyUrl() {
        KeyVaultKeyDriver portable = driver(VAULT, false);
        KeyVaultKeyDriver nativeNames = driver(VAULT, true);

        assertThat(portable.keyName("orders")).isEqualTo("orders");
        assertThat(nativeNames.keyName("orders")).isEqualTo("orders");
        assertThat(nativeNames.keyName(VAULT + "/keys/orders")).isEqualTo("orders");
        assertThatThrownBy(() -> nativeNames.keyName("https://other.vault.azure.net/keys/orders"))
                .isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> nativeNames.keyName(VAULT + "/keys/orders/v1"))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void anEntryWithoutAVersionIsRefusedBeforeAnyCall() {
        WrappedKey noVersion = new WrappedKey("azure-kv:" + VAULT + "/keys/orders", Optional.empty(),
                WrapAlgorithm.AZURE_RSA_OAEP_256, new byte[256]);

        assertThatThrownBy(() -> driver(VAULT, false).unwrapKey(noVersion)).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void rawEcdsaSignaturesBecomeDerThatTheJdkVerifies() throws Exception {
        for (String curve : List.of("secp256r1", "secp384r1")) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            KeyPair pair = generator.generateKeyPair();
            boolean p256 = curve.equals("secp256r1");
            byte[] data = "orders/o-1".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 20; i++) { // short R and S, and ones with the top bit set, come up within a few
                Signature raw = Signature.getInstance(p256 ? "SHA256withECDSAinP1363Format" : "SHA384withECDSAinP1363Format");
                raw.initSign(pair.getPrivate());
                raw.update(data);
                byte[] der = KeyVaultKeyDriver.derFromRaw(raw.sign(), p256 ? 32 : 48);

                Signature verifier = Signature.getInstance(p256 ? "SHA256withECDSA" : "SHA384withECDSA");
                verifier.initVerify(pair.getPublic());
                verifier.update(data);
                assertThat(verifier.verify(der)).as("%s signature %d", curve, i).isTrue();
            }
        }
    }

    @Test
    void purposesAlgorithmsAndTypesFollowTheJsonWebKey() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        KeyPair rsaPair = rsa.generateKeyPair();
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp384r1"));

        JsonWebKey both = JsonWebKey.fromRsa(rsaPair);
        JsonWebKey wrapOnly = JsonWebKey.fromRsa(rsaPair, List.of(KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY));
        JsonWebKey signOnly = JsonWebKey.fromRsa(rsaPair, List.of(KeyOperation.SIGN, KeyOperation.VERIFY));
        JsonWebKey p384 = JsonWebKey.fromEc(ec.generateKeyPair(), Security.getProvider("SunEC"), List.of(KeyOperation.SIGN));

        assertThat(KeyVaultKeyDriver.purposes(both)).containsExactlyInAnyOrder(KeyPurpose.ENCRYPTION,
                KeyPurpose.SIGNING);
        assertThat(KeyVaultKeyDriver.purposes(wrapOnly)).containsExactly(KeyPurpose.ENCRYPTION);
        assertThat(KeyVaultKeyDriver.purposes(signOnly)).containsExactly(KeyPurpose.SIGNING);
        assertThat(KeyVaultKeyDriver.keyType(both)).isEqualTo("RSA_2048");
        assertThat(KeyVaultKeyDriver.algorithms(signOnly)).containsExactlyInAnyOrder(SigningAlgorithm.RSA_PSS_SHA256,
                SigningAlgorithm.RSA_PKCS1_SHA256);
        assertThat(p384.getCurveName()).isEqualTo(KeyCurveName.P_384);
        assertThat(KeyVaultKeyDriver.purposes(p384)).containsExactly(KeyPurpose.SIGNING);
        assertThat(KeyVaultKeyDriver.keyType(p384)).isEqualTo("EC_P384");
        assertThat(KeyVaultKeyDriver.algorithms(p384)).containsExactly(SigningAlgorithm.ECDSA_P384_SHA384);
    }

    @Test
    void versionsAreTheEnabledOnesWithTheCurrentLast() throws Exception {
        OffsetDateTime same = OffsetDateTime.parse("2026-11-06T10:00:00Z");
        List<KeyProperties> listed = List.of(version("v3", same, true), version("v1", same.minusDays(2), true),
                version("v2", same, true), version("v0", same.minusDays(3), false));

        // v2 and v3 were made in the same second; v3 is current, so it goes last whatever the listing order.
        assertThat(KeyVaultKeyDriver.enabledOldestFirst(listed, "v3")).containsExactly("v1", "v2", "v3");
    }

    // Helpers

    private static KeyVaultKeyDriver driver(String vaultUrl, boolean nativeNames) {
        return new KeyVaultKeyDriver(null, null, vaultUrl, nativeNames, id -> {
            throw new AssertionError("no cryptography calls here");
        });
    }

    /** KeyProperties' version and creation time are set by the service, so they are set here by reflection. */
    private static KeyProperties version(String version, OffsetDateTime created, boolean enabled) throws Exception {
        KeyProperties properties = new KeyProperties().setEnabled(enabled);
        set(properties, "version", version);
        set(properties, "createdOn", created);
        return properties;
    }

    private static void set(KeyProperties properties, String field, Object value) throws Exception {
        Field f = KeyProperties.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(properties, value);
    }
}
