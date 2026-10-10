package org.kamranzafar.stratum.gcp;

import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretVersionName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
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

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** The parameter store TCK on Secret Manager emulated by Floci-gcp (gRPC). */
@Testcontainers(disabledWithoutDocker = true)
class SecretManagerConformanceIT {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.gcp.image", "floci/floci-gcp:0.10.0")))
            .withExposedPorts(4588)
            .waitingFor(Wait.forHttp("/health").forPort(4588));

    private static Stratum stratum;

    @BeforeAll
    static void start() {
        stratum = Stratum.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4588)))
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "floci-local"))
                .build();
    }

    @AfterAll
    static void stop() {
        if (stratum != null) {
            stratum.close();
        }
    }

    @Nested
    class Secrets extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }
    }

    @Test
    void secretIdsUseDoubleUnderscoreSeparators() {
        ParameterStore store = stratum.provider("gcp").parameterStore(ParameterStoreConfig.secrets().build());
        store.put("orders/db/password", "pw");

        var client = store.unwrap(SecretManagerServiceClient.class);
        assertThat(client.accessSecretVersion(SecretVersionName.of("floci-local", "orders__db__password", "latest"))
                .getPayload().getData().toStringUtf8()).isEqualTo("pw");
        store.delete("orders/db/password");
    }
}
