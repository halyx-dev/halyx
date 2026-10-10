package org.kamranzafar.stratum.azure;

import com.azure.security.keyvault.secrets.SecretClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.azure.parameters.AzureParameterProperties;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.parameters.ParameterStore;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.kamranzafar.stratum.tck.ParameterStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

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

    private static Stratum stratum;

    @BeforeAll
    static void start() {
        stratum = Stratum.builder()
                .register("azure", ProviderConfig.of("azure").credentials(Credentials.anonymous()))
                .build();
    }

    @AfterAll
    static void stop() {
        if (stratum != null) {
            stratum.close();
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
            return stratum.provider("azure");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return keyVault();
        }
    }

    @Nested
    class AppConfiguration extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("azure");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return appConfiguration(null);
        }
    }

    @Test
    void keyVaultNamesUseDoubleDashSeparators() {
        ParameterStore store = stratum.provider("azure").parameterStore(keyVault().build());
        store.put("orders/db/password", "pw");

        var client = store.unwrap(SecretClient.class);
        assertThat(client.getSecret("orders--db--password").getValue()).isEqualTo("pw");
        store.delete("orders/db/password");
    }

    @Test
    void appConfigurationLabelsSeparateVariants() {
        ParameterStore prod = stratum.provider("azure").parameterStore(appConfiguration("prod").prefix("labels").build());
        ParameterStore dev = stratum.provider("azure").parameterStore(appConfiguration("dev").prefix("labels").build());
        prod.put("pool/size", "50");
        dev.put("pool/size", "5");

        assertThat(prod.require("pool/size")).isEqualTo("50");
        assertThat(dev.require("pool/size")).isEqualTo("5");
        prod.delete("pool/size");
        dev.delete("pool/size");
    }
}
