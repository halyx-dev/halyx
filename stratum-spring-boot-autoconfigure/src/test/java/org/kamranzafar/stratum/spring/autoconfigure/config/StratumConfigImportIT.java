package org.kamranzafar.stratum.spring.autoconfigure.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code spring.config.import=stratum:<store>} end to end: secrets written to Vault become Spring
 * properties of an application started from a config file. Runs on Boot 3 and Boot 4.
 */
@Testcontainers(disabledWithoutDocker = true)
class StratumConfigImportIT {

    @Container
    static final GenericContainer<?> VAULT = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("vault.image", "hashicorp/vault:2.1.2")))
            .withExposedPorts(8200)
            .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root-token")
            .withCommand("server", "-dev")
            .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200));

    @TempDir
    static Path dir;

    @SpringBootConfiguration
    static class App {
    }

    private static URI address() {
        return URI.create("http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200));
    }

    @BeforeAll
    static void seed() {
        try (Stratum stratum = Stratum.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .credentials(Credentials.of("token", "root-token"))).build()) {
            var store = stratum.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("orders-svc").build());
            store.put("db/url", "jdbc:postgresql://db:5432/orders");
            store.put("db/password", "s3cr3t");
            store.put("feature/new-checkout", "true");
        }
    }

    private static ConfigurableApplicationContext start(String imports) throws Exception {
        Path file = Files.writeString(dir.resolve("application-" + Math.abs(imports.hashCode()) + ".properties"), """
                stratum.providers.vault.type=vault
                stratum.providers.vault.endpoint=%s
                stratum.providers.vault.credentials.type=static
                stratum.providers.vault.credentials.id=token
                stratum.providers.vault.credentials.secret=root-token
                stratum.parameter-stores.app.prefix=orders-svc
                spring.config.import=%s
                """.formatted(address(), imports));
        SpringApplication application = new SpringApplication(App.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run("--spring.config.location=file:" + file);
    }

    @Test
    void secretsBecomeSpringProperties() throws Exception {
        try (var context = start("stratum:app")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("db.url")).isEqualTo("jdbc:postgresql://db:5432/orders");
            assertThat(env.getProperty("db.password")).isEqualTo("s3cr3t");
            assertThat(env.getProperty("feature.new-checkout", Boolean.class)).isTrue();
        }
    }

    @Test
    void importCanBeNarrowedToAPrefix() throws Exception {
        try (var context = start("stratum:app/db")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("db.url")).isNotNull();
            assertThat(env.getProperty("feature.new-checkout")).isNull();
        }
    }

    @Test
    void unknownStoreFailsUnlessOptional() throws Exception {
        assertThatThrownBy(() -> start("stratum:missing")).hasStackTraceContaining("stratum.parameter-stores.missing");

        try (var context = start("optional:stratum:missing")) {
            assertThat(context.getEnvironment().getProperty("db.url")).isNull();
        }
    }
}
