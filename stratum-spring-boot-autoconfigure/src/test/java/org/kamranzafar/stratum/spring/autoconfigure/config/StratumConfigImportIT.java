package org.kamranzafar.stratum.spring.autoconfigure.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.parameters.ParameterStore;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.kamranzafar.stratum.spring.autoconfigure.StratumAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    /** Like an application's @SpringBootApplication, with only Stratum's auto-configuration. */
    @SpringBootConfiguration
    @ImportAutoConfiguration(StratumAutoConfiguration.class)
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
            store.put("rds/creds", "{\"username\":\"app\",\"password\":\"rds-pw\",\"port\":5432}");
            stratum.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("legacy-svc/").nativeNames(true).build())
                    .put("db_password", "legacy-pw");
        }
    }

    private static ParameterStore vaultStore(String prefix) {
        Stratum stratum = Stratum.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .credentials(Credentials.of("token", "root-token"))).build();
        return stratum.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix(prefix).build());
    }

    private static ConfigurableApplicationContext start(String imports) throws Exception {
        return start(imports, "", event -> { });
    }

    private static ConfigurableApplicationContext start(String imports, String extra,
                                                        ApplicationListener<StratumParametersChangedEvent> listener) throws Exception {
        Path file = Files.writeString(dir.resolve("application-" + Math.abs((imports + extra).hashCode()) + ".properties"), """
                stratum.providers.vault.type=vault
                stratum.providers.vault.endpoint=%s
                stratum.providers.vault.credentials.type=static
                stratum.providers.vault.credentials.id=token
                stratum.providers.vault.credentials.secret=root-token
                stratum.parameter-stores.app.prefix=orders-svc
                stratum.parameter-stores.legacy.prefix=legacy-svc/
                stratum.parameter-stores.legacy.native-names=true
                %s
                spring.config.import=%s
                """.formatted(address(), extra, imports));
        SpringApplication application = new SpringApplication(App.class);
        application.addListeners(listener);
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
    void jsonSecretsAreExpandedIntoFields() throws Exception {
        try (var context = start("stratum:app")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("rds.creds.password")).isEqualTo("rds-pw");
            assertThat(env.getProperty("rds.creds.port", Integer.class)).isEqualTo(5432);
            assertThat(env.getProperty("rds.creds")).startsWith("{");
        }
    }

    @Test
    void nativeNamesAreImportedAsTheyAre() throws Exception {
        try (var context = start("stratum:legacy")) {
            assertThat(context.getEnvironment().getProperty("db_password")).isEqualTo("legacy-pw");
        }
    }

    @Test
    void reloadUpdatesPropertiesAndPublishesAnEvent() throws Exception {
        BlockingQueue<StratumParametersChangedEvent> events = new LinkedBlockingQueue<>();
        ParameterStore store = vaultStore("orders-svc");
        store.put("rotating/password", "before");
        try (var context = start("stratum:app", "stratum.parameter-stores.app.reload-interval=PT0.3S", events::add)) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("rotating.password")).isEqualTo("before");

            store.put("rotating/password", "after");

            StratumParametersChangedEvent event = events.poll(15, TimeUnit.SECONDS);
            assertThat(event).as("change event").isNotNull();
            assertThat(event.store()).isEqualTo("app");
            assertThat(event.keys()).contains("rotating.password");
            assertThat(env.getProperty("rotating.password")).isEqualTo("after");
        } finally {
            store.delete("rotating/password");
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
