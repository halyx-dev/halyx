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
package dev.halyx.spring.cloudparameters;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.parameters.ParameterKind;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.config.HalyxParametersChangedEvent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot with a real cloud's parameters and secrets (tier 1): {@code spring.config.import=halyx:} loading a
 * store into the {@code Environment}, JSON secrets expanded into fields, a rotation reloaded in place with its event
 * and followed by the store bean's own cache, and the store's health check, on Boot 3.5 and 4. Runs only with
 * {@code -Dhalyx.real.spring.parameters=true}, from the real-cloud scripts' tier 1, which pass the provider type
 * and properties, the kinds to test ({@code halyx.real.spring.parameter-kinds}, default {@code secrets,parameters})
 * and, where the cloud needs one, each kind's location ({@code .secrets-location}, {@code .parameters-location}: the
 * Key Vault and App Configuration endpoints on Azure). Values live under a prefix of their own, deleted at the end.
 */
@EnabledIfSystemProperty(named = "halyx.real.spring.parameters", matches = "true")
class RealSpringParametersIT {

    // tck-*: the names the test identities may write, as for the conformance kits
    static final String PREFIX = "tck-spring-" + UUID.randomUUID().toString().substring(0, 8);

    /** Listings and overwrites are eventually consistent on some backends (Secrets Manager, Secret Manager). */
    static final Duration LAG = Duration.ofMinutes(3);

    @TempDir
    static Path dir;

    private static Halyx halyx;

    /** Like an application's @SpringBootApplication, with only Halyx's auto-configuration. */
    @SpringBootConfiguration
    @ImportAutoConfiguration(HalyxAutoConfiguration.class)
    static class App {
    }

    static Stream<ParameterKind> kinds() {
        return Arrays.stream(System.getProperty("halyx.real.spring.parameter-kinds", "secrets,parameters").split(","))
                .map(String::trim).filter(k -> !k.isEmpty()).map(k -> ParameterKind.valueOf(k.toUpperCase()));
    }

    private static String provider() {
        return System.getProperty("halyx.real.spring.provider");
    }

    private static String[] providerProperties() {
        return System.getProperty("halyx.real.spring.provider-properties", "").split(",");
    }

    private static String location(ParameterKind kind) {
        return System.getProperty("halyx.real.spring." + kind.name().toLowerCase() + "-location", "");
    }

    @BeforeAll
    static void connect() {
        ProviderConfig config = ProviderConfig.of(provider());
        for (String pair : providerProperties()) {
            if (!pair.isBlank()) {
                String[] kv = pair.trim().split("=", 2);
                config = config.property(kv[0], kv[1]);
            }
        }
        halyx = Halyx.builder().register("cloud", config).build();
    }

    @AfterAll
    static void close() {
        if (halyx != null) {
            halyx.close();
        }
    }

    /** The store the application imports, written to directly by the test. */
    private static ParameterStore backend(ParameterKind kind) {
        ParameterStoreConfig.Builder config = ParameterStoreConfig.builder(kind).prefix(PREFIX + "-" + kind.name().toLowerCase());
        if (!location(kind).isBlank()) {
            config.location(location(kind));
        }
        return halyx.provider("cloud").parameterStore(config.build());
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(LAG);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as(what).isBefore(deadline);
            Thread.sleep(2_000);
        }
    }

    private static ConfigurableApplicationContext start(ParameterKind kind,
                                                        BlockingQueue<HalyxParametersChangedEvent> events) throws Exception {
        StringBuilder file = new StringBuilder("halyx.providers.cloud.type=" + provider() + "\n");
        for (String pair : providerProperties()) {
            if (!pair.isBlank()) {
                file.append("halyx.providers.cloud.properties.").append(pair.trim()).append('\n');
            }
        }
        file.append("halyx.parameter-stores.app.kind=").append(kind.name().toLowerCase()).append('\n')
                .append("halyx.parameter-stores.app.prefix=").append(PREFIX).append('-').append(kind.name().toLowerCase())
                .append('\n')
                .append("halyx.parameter-stores.app.cache-ttl=PT1H\n")
                .append("halyx.parameter-stores.app.reload-interval=PT5S\n")
                .append("halyx.parameter-stores.app.startup-attempts=5\n");
        if (!location(kind).isBlank()) {
            file.append("halyx.parameter-stores.app.location=").append(location(kind)).append('\n');
        }
        file.append("spring.config.import=halyx:app\n");
        Path properties = Files.writeString(dir.resolve("application-" + kind.name().toLowerCase() + ".properties"),
                file.toString());
        SpringApplication application = new SpringApplication(App.class);
        application.addListeners((ApplicationListener<HalyxParametersChangedEvent>) events::add);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run("--spring.config.location=file:" + properties);
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void importsReloadsAndChecksHealthOnTheRealBackend(ParameterKind kind) throws Exception {
        ParameterStore backend = backend(kind);
        backend.put("db/password", "before");
        backend.put("rds/creds", "{\"username\":\"app\",\"password\":\"rds-pw\"}");
        try {
            await("both values listed", () -> backend.getAll("").keySet().containsAll(List.of("db/password", "rds/creds")));
            BlockingQueue<HalyxParametersChangedEvent> events = new LinkedBlockingQueue<>();
            try (var context = start(kind, events)) {
                var env = context.getEnvironment();
                assertThat(env.getProperty("db.password")).isEqualTo("before");
                assertThat(env.getProperty("rds.creds.password")).isEqualTo("rds-pw");
                ParameterStore bean = context.getBean("app", ParameterStore.class);
                bean.ping();
                assertThat(bean.require("db/password")).isEqualTo("before");

                backend.put("db/password", "after");

                Instant deadline = Instant.now().plus(LAG);
                while (!"after".equals(env.getProperty("db.password"))) {
                    assertThat(Instant.now()).as("rotation reloaded").isBefore(deadline);
                    events.poll(5, TimeUnit.SECONDS);
                }
                assertThat(bean.require("db/password")).isEqualTo("after");
            }
        } finally {
            backend.delete("db/password");
            backend.delete("rds/creds");
        }
    }
}
