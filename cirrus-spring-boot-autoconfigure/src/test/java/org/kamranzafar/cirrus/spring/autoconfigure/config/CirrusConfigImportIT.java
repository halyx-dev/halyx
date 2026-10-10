package org.kamranzafar.cirrus.spring.autoconfigure.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.kamranzafar.cirrus.spring.autoconfigure.CirrusAutoConfiguration;
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
 * {@code spring.config.import=cirrus:<store>} end to end: secrets written to Vault become Spring
 * properties of an application started from a config file. Runs on Boot 3 and Boot 4.
 */
@Testcontainers(disabledWithoutDocker = true)
class CirrusConfigImportIT {

    @Container
    static final GenericContainer<?> VAULT = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("vault.image", "hashicorp/vault:2.1.2")))
            .withExposedPorts(8200)
            .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root-token")
            .withCommand("server", "-dev")
            .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200));

    @TempDir
    static Path dir;

    /** Like an application's @SpringBootApplication, with only Cirrus's auto-configuration. */
    @SpringBootConfiguration
    @ImportAutoConfiguration(CirrusAutoConfiguration.class)
    static class App {
    }

    private static URI address() {
        return URI.create("http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200));
    }

    @BeforeAll
    static void seed() {
        try (Cirrus cirrus = Cirrus.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .credentials(Credentials.of("token", "root-token"))).build()) {
            var store = cirrus.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("orders-svc").build());
            store.put("db/url", "jdbc:postgresql://db:5432/orders");
            store.put("db/password", "s3cr3t");
            store.put("feature/new-checkout", "true");
            store.put("rds/creds", "{\"username\":\"app\",\"password\":\"rds-pw\",\"port\":5432}");
            cirrus.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("legacy-svc/").nativeNames(true).build())
                    .put("db_password", "legacy-pw");
        }
    }

    private static ParameterStore vaultStore(String prefix) {
        Cirrus cirrus = Cirrus.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .credentials(Credentials.of("token", "root-token"))).build();
        return cirrus.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix(prefix).build());
    }

    private static ConfigurableApplicationContext start(String imports) throws Exception {
        return start(imports, "", event -> { });
    }

    private static ConfigurableApplicationContext start(String imports, String extra,
                                                        ApplicationListener<CirrusParametersChangedEvent> listener) throws Exception {
        Path file = Files.writeString(dir.resolve("application-" + Math.abs((imports + extra).hashCode()) + ".properties"), """
                cirrus.providers.vault.type=vault
                cirrus.providers.vault.endpoint=%s
                cirrus.providers.vault.credentials.type=static
                cirrus.providers.vault.credentials.id=token
                cirrus.providers.vault.credentials.secret=root-token
                cirrus.parameter-stores.app.prefix=orders-svc
                cirrus.parameter-stores.legacy.prefix=legacy-svc/
                cirrus.parameter-stores.legacy.native-names=true
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
        try (var context = start("cirrus:app")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("db.url")).isEqualTo("jdbc:postgresql://db:5432/orders");
            assertThat(env.getProperty("db.password")).isEqualTo("s3cr3t");
            assertThat(env.getProperty("feature.new-checkout", Boolean.class)).isTrue();
        }
    }

    @Test
    void jsonSecretsAreExpandedIntoFields() throws Exception {
        try (var context = start("cirrus:app")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("rds.creds.password")).isEqualTo("rds-pw");
            assertThat(env.getProperty("rds.creds.port", Integer.class)).isEqualTo(5432);
            assertThat(env.getProperty("rds.creds")).startsWith("{");
        }
    }

    @Test
    void nativeNamesAreImportedAsTheyAre() throws Exception {
        try (var context = start("cirrus:legacy")) {
            assertThat(context.getEnvironment().getProperty("db_password")).isEqualTo("legacy-pw");
        }
    }

    @Test
    void reloadUpdatesPropertiesAndPublishesAnEvent() throws Exception {
        BlockingQueue<CirrusParametersChangedEvent> events = new LinkedBlockingQueue<>();
        ParameterStore store = vaultStore("orders-svc");
        store.put("rotating/password", "before");
        try (var context = start("cirrus:app", "cirrus.parameter-stores.app.reload-interval=PT0.3S", events::add)) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("rotating.password")).isEqualTo("before");

            store.put("rotating/password", "after");

            CirrusParametersChangedEvent event = events.poll(15, TimeUnit.SECONDS);
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
        try (var context = start("cirrus:app/db")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("db.url")).isNotNull();
            assertThat(env.getProperty("feature.new-checkout")).isNull();
        }
    }

    @Test
    void unknownStoreFailsUnlessOptional() throws Exception {
        assertThatThrownBy(() -> start("cirrus:missing")).hasStackTraceContaining("cirrus.parameter-stores.missing");

        try (var context = start("optional:cirrus:missing")) {
            assertThat(context.getEnvironment().getProperty("db.url")).isNull();
        }
    }
}
