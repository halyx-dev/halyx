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

import java.util.OptionalInt;
import dev.halyx.tck.ParameterEncryptionConformanceTest;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStoreConfig;
import com.azure.core.credential.AccessToken;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.models.CreateRsaKeyOptions;
import com.azure.security.keyvault.keys.models.KeyOperation;
import com.azure.security.keyvault.secrets.SecretClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.azure.parameters.AzureParameterProperties;
import dev.halyx.azure.parameters.ForceHttpPolicy;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.tck.ParameterStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The parameter store TCK on Key Vault and App Configuration emulated by Floci-az, plain and through encrypted views. */
@Testcontainers(disabledWithoutDocker = true)
class AzureParameterStoreConformanceIT {

    private static final String ACCOUNT = "devstoreaccount1";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.azure.image", "floci/floci-az:0.14.0")))
            .withExposedPorts(4577)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;
    private static CryptoKey key;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .register("azure", ProviderConfig.of("azure").credentials(Credentials.anonymous()))
                .build();
        // A key in Floci-az's vault, whose client is non-blocking as the stores' are
        new KeyClientBuilder().vaultUrl(base() + "-keyvault")
                .credential(request -> Mono.just(new AccessToken("emulator", OffsetDateTime.now().plusHours(1))))
                .addPolicy(new ForceHttpPolicy()).disableChallengeResourceVerification().buildClient()
                .createRsaKey(new CreateRsaKeyOptions("tck-parameters").setKeySize(2048).setKeyOperations(
                        KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY));
        key = halyx.provider("azure").keyStore(KeyStoreConfig.builder().location(base() + "-keyvault")
                .property(AzureParameterProperties.INSECURE_HTTP, "true").build()).key("tck-parameters");
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    private static String base() {
        // https is what the SDKs require; the emulator policy downgrades it to http on the wire.
        return "https://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4577) + "/" + ACCOUNT;
    }

    static ParameterStoreConfig.Builder keyVault() {
        return ParameterStoreConfig.secrets().location(base() + "-keyvault")
                .property(AzureParameterProperties.INSECURE_HTTP, "true");
    }

    static ParameterStoreConfig.Builder appConfiguration(String label) {
        var builder = ParameterStoreConfig.parameters()
                .property(AzureParameterProperties.APPCONFIG_CONNECTION_STRING,
                        "Endpoint=" + base() + "-appconfig;Id=" + ACCOUNT + ";Secret=cGxhY2Vob2xkZXI=")
                .property(AzureParameterProperties.INSECURE_HTTP, "true");
        return label == null ? builder : builder.property(AzureParameterProperties.APPCONFIG_LABEL, label);
    }

    @Nested
    class KeyVault extends ParameterStoreConformanceTest {
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
            return halyx.provider("azure");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return keyVault();
        }

        /** Key Vault names allow only letters, digits and dashes; "--" is native but not portable. */
        @Override
        protected String nativePrefix(String unique) {
            return unique + "-";
        }

        @Override
        protected String nativeName() {
            return "db--password";
        }

        /** Key Vault deletes and recoveries finish asynchronously, and the driver doesn't wait for them. */
        @Override
        protected Duration deleteLag() {
            return Duration.ofSeconds(30);
        }
    }

    @Nested
    class AppConfiguration extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("azure");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return appConfiguration(null);
        }
    }

    @Nested
    class EncryptedKeyVault extends KeyVault {
        @Override
        protected ParameterStore wrap(ParameterStore store) {
            return store.encryptedWith(key);
        }
    }

    @Nested
    class KeyVaultEncryption extends ParameterEncryptionConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("azure");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return keyVault();
        }

        @Override
        protected OptionalInt maxValueBytes() {
            return OptionalInt.of(25 * 1024);
        }
    }

    @Nested
    class EncryptedAppConfiguration extends AppConfiguration {
        @Override
        protected ParameterStore wrap(ParameterStore store) {
            return store.encryptedWith(key);
        }
    }

    @Nested
    class AppConfigurationEncryption extends ParameterEncryptionConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("azure");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return appConfiguration(null);
        }

        @Override
        protected OptionalInt maxValueBytes() {
            return OptionalInt.of(10 * 1024);
        }
    }

    @Test
    void keyVaultNamesUseDoubleDashSeparators() {
        ParameterStore store = halyx.provider("azure").parameterStore(keyVault().build());
        store.put("orders/db/password", "pw");

        var client = store.unwrap(SecretClient.class);
        assertThat(client.getSecret("orders--db--password").getValue()).isEqualTo("pw");
        store.delete("orders/db/password");
    }

    @Test
    void appConfigurationLabelsSeparateVariants() {
        ParameterStore prod = halyx.provider("azure").parameterStore(appConfiguration("prod").prefix("labels").build());
        ParameterStore dev = halyx.provider("azure").parameterStore(appConfiguration("dev").prefix("labels").build());
        prod.put("pool/size", "50");
        dev.put("pool/size", "5");

        assertThat(prod.require("pool/size")).isEqualTo("50");
        assertThat(dev.require("pool/size")).isEqualTo("5");
        prod.delete("pool/size");
        dev.delete("pool/size");
    }
}
