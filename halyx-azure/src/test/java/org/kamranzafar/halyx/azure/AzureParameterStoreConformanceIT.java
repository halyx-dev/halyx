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
package org.kamranzafar.halyx.azure;

import com.azure.security.keyvault.secrets.SecretClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.CloudContext;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.azure.parameters.AzureParameterProperties;
import org.kamranzafar.halyx.config.Credentials;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.parameters.ParameterStore;
import org.kamranzafar.halyx.parameters.ParameterStoreConfig;
import org.kamranzafar.halyx.tck.ParameterStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The parameter store TCK on Key Vault and App Configuration emulated by Floci-az. */
@Testcontainers(disabledWithoutDocker = true)
class AzureParameterStoreConformanceIT {

    private static final String ACCOUNT = "devstoreaccount1";

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.azure.image", "floci/floci-az:0.14.0")))
            .withExposedPorts(4577)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .register("azure", ProviderConfig.of("azure").credentials(Credentials.anonymous()))
                .build();
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
        protected CloudContext context() {
            return halyx.provider("azure");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return appConfiguration(null);
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
