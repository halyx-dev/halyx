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
package dev.halyx.spring.autoconfigure.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code spring.config.import=halyx:<store>} end to end: secrets written to Vault become Spring
 * properties of an application started from a config file. Runs on Boot 3 and Boot 4.
 */
@Testcontainers(disabledWithoutDocker = true)
class HalyxConfigImportIT {

    @Container
    static final GenericContainer<?> VAULT = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("vault.image", "hashicorp/vault:2.1.2")))
            .withExposedPorts(8200)
            .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root-token")
            .withCommand("server", "-dev")
            .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200));

    @TempDir
    static Path dir;

    /** Like an application's @SpringBootApplication, with only Halyx's auto-configuration. */
    @SpringBootConfiguration
    @ImportAutoConfiguration(HalyxAutoConfiguration.class)
    static class App {
    }

    private static URI address() {
        return URI.create("http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200));
    }

    @BeforeAll
    static void seed() {
        try (Halyx halyx = Halyx.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .credentials(Credentials.of("token", "root-token"))).build()) {
            var store = halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("orders-svc").build());
            store.put("db/url", "jdbc:postgresql://db:5432/orders");
            store.put("db/password", "s3cr3t");
            store.put("feature/new-checkout", "true");
            store.put("rds/creds", "{\"username\":\"app\",\"password\":\"rds-pw\",\"port\":5432}");
            halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("legacy-svc/").nativeNames(true).build())
                    .put("db_password", "legacy-pw");
            sealedSeed(halyx);
        }
    }

    /** Values sealed with the Transit key {@code config}, under {@code sealed-svc}, and one in the clear beside them. */
    private static void sealedSeed(Halyx halyx) {
        vault("POST", "/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
        vault("POST", "/v1/transit/keys/config", "{}");
        ParameterStore plain = halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("sealed-svc")
                .build());
        var sealed = plain.encryptedWith(halyx.provider("vault").keyStore().key("config"));
        sealed.put("db/password", "sealed-pw");
        sealed.put("rds/creds", "{\"username\":\"app\",\"password\":\"sealed-rds-pw\"}");
        halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("mixed-svc").build())
                .put("legacy/flag", "in-the-clear");
        halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("mixed-svc").build())
                .encryptedWith(halyx.provider("vault").keyStore().key("config")).put("db/password", "mixed-pw");
    }

    private static void vault(String method, String path, String body) {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(address().resolve(path))
                    .header("X-Vault-Token", "root-token").method(method, HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(method + " " + path + ": " + response.body()).isLessThan(300);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ParameterStore vaultStore(String prefix) {
        Halyx halyx = Halyx.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .credentials(Credentials.of("token", "root-token"))).build();
        return halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix(prefix).build());
    }

    private static ConfigurableApplicationContext start(String imports) throws Exception {
        return start(imports, "", event -> { });
    }

    private static ConfigurableApplicationContext start(String imports, String extra,
                                                        ApplicationListener<HalyxParametersChangedEvent> listener) throws Exception {
        Path file = Files.writeString(dir.resolve("application-" + Math.abs((imports + extra).hashCode()) + ".properties"), """
                halyx.providers.vault.type=vault
                halyx.providers.vault.endpoint=%s
                halyx.providers.vault.credentials.type=static
                halyx.providers.vault.credentials.id=token
                halyx.providers.vault.credentials.secret=root-token
                halyx.parameter-stores.app.prefix=orders-svc
                halyx.parameter-stores.legacy.prefix=legacy-svc/
                halyx.parameter-stores.legacy.native-names=true
                halyx.key-stores.kms.provider=vault
                halyx.parameter-stores.sealed.prefix=sealed-svc
                halyx.parameter-stores.sealed.encryption-key.name=config
                halyx.parameter-stores.mixed.prefix=mixed-svc
                halyx.parameter-stores.mixed.encryption-key.name=config
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
        try (var context = start("halyx:app")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("db.url")).isEqualTo("jdbc:postgresql://db:5432/orders");
            assertThat(env.getProperty("db.password")).isEqualTo("s3cr3t");
            assertThat(env.getProperty("feature.new-checkout", Boolean.class)).isTrue();
        }
    }

    @Test
    void jsonSecretsAreExpandedIntoFields() throws Exception {
        try (var context = start("halyx:app")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("rds.creds.password")).isEqualTo("rds-pw");
            assertThat(env.getProperty("rds.creds.port", Integer.class)).isEqualTo(5432);
            assertThat(env.getProperty("rds.creds")).startsWith("{");
        }
    }

    @Test
    void nativeNamesAreImportedAsTheyAre() throws Exception {
        try (var context = start("halyx:legacy")) {
            assertThat(context.getEnvironment().getProperty("db_password")).isEqualTo("legacy-pw");
        }
    }

    @Test
    void reloadUpdatesPropertiesAndPublishesAnEvent() throws Exception {
        BlockingQueue<HalyxParametersChangedEvent> events = new LinkedBlockingQueue<>();
        ParameterStore store = vaultStore("orders-svc");
        store.put("rotating/password", "before");
        try (var context = start("halyx:app", "halyx.parameter-stores.app.reload-interval=PT0.3S", events::add)) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("rotating.password")).isEqualTo("before");

            store.put("rotating/password", "after");

            HalyxParametersChangedEvent event = events.poll(15, TimeUnit.SECONDS);
            assertThat(event).as("change event").isNotNull();
            assertThat(event.store()).isEqualTo("app");
            assertThat(event.keys()).contains("rotating.password");
            assertThat(env.getProperty("rotating.password")).isEqualTo("after");
        } finally {
            store.delete("rotating/password");
        }
    }

    @Test
    void reloadAlsoRefreshesTheStoreBeansCache() throws Exception {
        BlockingQueue<HalyxParametersChangedEvent> events = new LinkedBlockingQueue<>();
        ParameterStore backend = vaultStore("orders-svc");
        backend.put("shared/password", "before");
        try (var context = start("halyx:app",
                "halyx.parameter-stores.app.reload-interval=PT0.3S\nhalyx.parameter-stores.app.cache-ttl=PT1H", events::add)) {
            ParameterStore bean = context.getBean("app", ParameterStore.class);
            assertThat(bean.require("shared/password")).isEqualTo("before");

            backend.put("shared/password", "after");

            assertThat(events.poll(15, TimeUnit.SECONDS)).as("change event").isNotNull();
            // The watch runs on the bean itself, so its hour-long cache follows the Environment.
            assertThat(bean.require("shared/password")).isEqualTo("after");
        } finally {
            backend.delete("shared/password");
        }
    }

    @Test
    void importCanBeNarrowedToAPrefix() throws Exception {
        try (var context = start("halyx:app/db")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("db.url")).isNotNull();
            assertThat(env.getProperty("feature.new-checkout")).isNull();
        }
    }

    @Test
    void unknownStoreFailsUnlessOptional() throws Exception {
        assertThatThrownBy(() -> start("halyx:missing")).hasStackTraceContaining("halyx.parameter-stores.missing");

        try (var context = start("optional:halyx:missing")) {
            assertThat(context.getEnvironment().getProperty("db.url")).isNull();
        }
    }

    // Encrypted stores

    @Test
    void sealedValuesAreOpenedAtStartup() throws Exception {
        try (var context = start("halyx:sealed")) {
            var env = context.getEnvironment();

            assertThat(env.getProperty("db.password")).isEqualTo("sealed-pw");
            assertThat(env.getProperty("rds.creds.password")).as("JSON fields of an opened value")
                    .isEqualTo("sealed-rds-pw");
            assertThat(context.getBean("sealed", ParameterStore.class).require("db/password")).isEqualTo("sealed-pw");
        }
        assertThat(vaultStore("sealed-svc").require("db/password")).as("stored sealed").startsWith("hlxs1:");
    }

    @Test
    void theKeyStoreMayBeOnAnotherProvider() throws Exception {
        String other = """
                halyx.providers.transit.type=vault
                halyx.providers.transit.endpoint=%s
                halyx.providers.transit.credentials.type=static
                halyx.providers.transit.credentials.id=token
                halyx.providers.transit.credentials.secret=root-token
                halyx.key-stores.kms.provider=transit
                halyx.parameter-stores.sealed.provider=vault
                halyx.parameter-stores.app.provider=vault
                halyx.parameter-stores.legacy.provider=vault
                halyx.parameter-stores.mixed.provider=vault
                """.formatted(address());
        try (var context = start("halyx:sealed", other, event -> { })) {
            assertThat(context.getEnvironment().getProperty("db.password")).isEqualTo("sealed-pw");
        }
    }

    @Test
    void plaintextFailsTheImportUnlessAllowed() throws Exception {
        assertThatThrownBy(() -> start("halyx:mixed")).hasStackTraceContaining("mixed-svc/legacy/flag")
                .hasStackTraceContaining("isn't encrypted");

        try (var context = start("halyx:mixed", "halyx.parameter-stores.mixed.allow-plaintext=true", event -> { })) {
            var env = context.getEnvironment();
            assertThat(env.getProperty("legacy.flag")).isEqualTo("in-the-clear");
            assertThat(env.getProperty("db.password")).isEqualTo("mixed-pw");
        }
    }

    @Test
    void aKeyStoreSettingOnlyTheImportWouldSetIsRefused() throws Exception {
        assertThatThrownBy(() -> start("halyx:sealed", "halyx.key-stores.kms.location=${transit.mount}", event -> { }))
                .hasStackTraceContaining("Unresolved placeholders in halyx.key-stores.kms.location")
                .hasStackTraceContaining("read before its import");
    }

    @Test
    void reloadOpensChangedValues() throws Exception {
        BlockingQueue<HalyxParametersChangedEvent> events = new LinkedBlockingQueue<>();
        try (Halyx halyx = Halyx.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .credentials(Credentials.of("token", "root-token"))).build()) {
            var sealed = halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("sealed-svc").build())
                    .encryptedWith(halyx.provider("vault").keyStore().key("config"));
            sealed.put("rotating/password", "before");
            try (var context = start("halyx:sealed", "halyx.parameter-stores.sealed.reload-interval=PT0.3S",
                    events::add)) {
                var env = context.getEnvironment();
                assertThat(env.getProperty("rotating.password")).isEqualTo("before");

                sealed.put("rotating/password", "after");

                assertThat(events.poll(15, TimeUnit.SECONDS)).as("change event").isNotNull();
                assertThat(env.getProperty("rotating.password")).isEqualTo("after");
            } finally {
                sealed.delete("rotating/password");
            }
        }
    }
}
