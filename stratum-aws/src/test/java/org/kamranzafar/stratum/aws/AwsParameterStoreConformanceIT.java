package org.kamranzafar.stratum.aws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.aws.parameters.SsmParameterDriver;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.kamranzafar.stratum.tck.ParameterStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.ParameterType;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** The parameter store TCK on SSM Parameter Store and Secrets Manager emulated by Floci. */
@Testcontainers(disabledWithoutDocker = true)
class AwsParameterStoreConformanceIT {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static Stratum stratum;

    @BeforeAll
    static void start() {
        stratum = Stratum.builder()
                .register("aws", ProviderConfig.of("aws")
                        .region("us-east-1")
                        .endpoint(URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566)))
                        .credentials(Credentials.of("test", "test")))
                .build();
    }

    @AfterAll
    static void stop() {
        if (stratum != null) {
            stratum.close();
        }
    }

    @Nested
    class SsmParameterStore extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("aws");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected String nativePrefix(String unique) {
            return "/" + unique + "/";
        }
    }

    @Nested
    class SecretsManager extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("aws");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }
    }

    @Test
    void secureParametersAreWrittenAsSecureString() {
        var store = stratum.provider("aws").parameterStore(ParameterStoreConfig.parameters()
                .prefix("secure-check").property(SsmParameterDriver.SECURE, "true").build());
        store.put("token", "s3cr3t");

        var native_ = store.unwrap(SsmClient.class).getParameter(b -> b.name("/secure-check/token")).parameter();
        assertThat(native_.type()).isEqualTo(ParameterType.SECURE_STRING);
        assertThat(store.require("token")).isEqualTo("s3cr3t");
        store.delete("token");
    }
}
