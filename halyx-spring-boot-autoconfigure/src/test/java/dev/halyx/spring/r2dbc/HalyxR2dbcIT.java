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
package dev.halyx.spring.r2dbc;

import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Data R2DBC over {@code halyx.r2dbc-databases}, on PostgreSQL in Docker, with Spring Boot's own R2DBC
 * and Spring Data R2DBC auto-configuration, on Boot 3.5 and 4.
 */
@Testcontainers(disabledWithoutDocker = true)
class HalyxR2dbcIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:17-alpine")
            .withEnv("POSTGRES_USER", "app")
            .withEnv("POSTGRES_PASSWORD", "app-secret")
            .withEnv("POSTGRES_DB", "app")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    /** Stands in for the application class: its package is scanned for repositories. */
    @Configuration(proxyBeanMethods = false)
    @AutoConfigurationPackage
    static class Application {
    }

    /** Boot's R2DBC auto-configuration: in spring-boot-autoconfigure on Boot 3, in its own modules on Boot 4. */
    static AutoConfigurations configurations() {
        List<Class<?>> classes = new ArrayList<>(List.of(HalyxAutoConfiguration.class));
        for (String[] names : List.of(
                new String[] {"org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration",
                        "org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration"},
                new String[] {"org.springframework.boot.autoconfigure.data.r2dbc.R2dbcDataAutoConfiguration",
                        "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration"},
                new String[] {"org.springframework.boot.autoconfigure.data.r2dbc.R2dbcRepositoriesAutoConfiguration",
                        "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcRepositoriesAutoConfiguration"})) {
            classes.add(firstPresent(names));
        }
        return AutoConfigurations.of(classes.toArray(Class<?>[]::new));
    }

    private static Class<?> firstPresent(String... names) {
        for (String name : names) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException notThisBoot) {
                // try the next
            }
        }
        throw new IllegalStateException("None of " + List.of(names) + " is on the classpath");
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(configurations())
            .withUserConfiguration(Application.class)
            .withPropertyValues(
                    "halyx.providers.aws.type=aws",
                    "halyx.providers.aws.region=eu-west-2",
                    "halyx.providers.aws.credentials.type=static",
                    "halyx.providers.aws.credentials.id=test",
                    "halyx.providers.aws.credentials.secret=test",
                    "halyx.r2dbc-databases.billing.engine=postgresql",
                    "halyx.r2dbc-databases.billing.host=" + POSTGRES.getHost(),
                    "halyx.r2dbc-databases.billing.port=" + POSTGRES.getMappedPort(5432),
                    "halyx.r2dbc-databases.billing.database=app",
                    "halyx.r2dbc-databases.billing.auth.type=password",
                    "halyx.r2dbc-databases.billing.auth.username=app",
                    "halyx.r2dbc-databases.billing.auth.password=app-secret",
                    "halyx.r2dbc-databases.billing.pool.max-size=4");

    @Test
    void springDataR2dbcUsesTheHalyxConnectionFactory() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(ConnectionFactory.class)).containsOnlyKeys("billing");
            assertThat(context.getBean("billing")).isInstanceOf(ConnectionPool.class);

            DatabaseClient client = context.getBean(DatabaseClient.class);
            client.sql("CREATE TABLE IF NOT EXISTS invoice (id BIGSERIAL PRIMARY KEY, customer TEXT, total NUMERIC)")
                    .then().block(WAIT);

            InvoiceRepository invoices = context.getBean(InvoiceRepository.class);
            invoices.save(new Invoice(null, "acme", new BigDecimal("12.50"))).block(WAIT);
            invoices.save(new Invoice(null, "acme", new BigDecimal("7.25"))).block(WAIT);
            invoices.save(new Invoice(null, "globex", BigDecimal.ONE)).block(WAIT);

            assertThat(invoices.findByCustomerOrderByIdAsc("acme").map(Invoice::total).collectList().block(WAIT))
                    .usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(new BigDecimal("12.50"), new BigDecimal("7.25"));
            assertThat(client.sql("SELECT count(*) FROM invoice").map(row -> row.get(0, Long.class)).one().block(WAIT))
                    .isEqualTo(3L);
        });
    }

    @Test
    void withoutHalyxDatabasesBootConfiguresR2dbcItself() {
        new ApplicationContextRunner()
                .withConfiguration(configurations())
                .withUserConfiguration(Application.class)
                .withPropertyValues("halyx.providers.aws.type=aws", "halyx.providers.aws.region=eu-west-2",
                        "spring.r2dbc.url=r2dbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/app",
                        "spring.r2dbc.username=app", "spring.r2dbc.password=app-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean("billing");
                    assertThat(context.getBeansOfType(ConnectionFactory.class)).hasSize(1);
                });
    }
}
