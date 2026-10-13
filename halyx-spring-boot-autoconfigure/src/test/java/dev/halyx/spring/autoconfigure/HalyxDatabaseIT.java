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
package dev.halyx.spring.autoconfigure;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import dev.halyx.parameters.ParameterStore;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code halyx.databases} against a real PostgreSQL, with the password held in a Halyx secret store. */
@Testcontainers(disabledWithoutDocker = true)
class HalyxDatabaseIT {

    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:17-alpine")
            .withEnv("POSTGRES_USER", "app")
            .withEnv("POSTGRES_PASSWORD", "initial-secret")
            .withEnv("POSTGRES_DB", "orders")
            .withExposedPorts(5432)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class));

    private String[] properties(String... auth) {
        String[] base = {
                "halyx.providers.aws.type=aws",
                "halyx.providers.aws.region=us-east-1",
                "halyx.providers.local.type=memory",
                "halyx.parameter-stores.secrets.provider=local",
                "halyx.databases.orders.provider=aws",
                "halyx.databases.orders.engine=postgresql",
                "halyx.databases.orders.host=" + POSTGRES.getHost(),
                "halyx.databases.orders.port=" + POSTGRES.getMappedPort(5432),
                "halyx.databases.orders.database=orders",
                "halyx.databases.orders.auth.username=app",
                "halyx.databases.orders.pool.max-size=3",
                "halyx.databases.orders.pool.min-idle=0"
        };
        String[] all = new String[base.length + auth.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(auth, 0, all, base.length, auth.length);
        return all;
    }

    @Test
    void registersAPrimaryPooledDataSource() {
        runner.withPropertyValues(properties("halyx.databases.orders.auth.password=initial-secret")).run(context -> {
            DataSource dataSource = context.getBean("orders", DataSource.class);

            assertThat(context.getBean(DataSource.class)).isSameAs(dataSource);
            assertThat(context.getBeanFactory().getBeanDefinition("orders").isPrimary()).isTrue();
            assertThat(dataSource).isInstanceOf(HikariDataSource.class);
            assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isEqualTo(3);
            assertThat(query(dataSource, "SELECT current_database()")).isEqualTo("orders");
        });
    }

    @Test
    void passwordFromASecretFollowsRotation() {
        runner.withPropertyValues(properties(
                        "halyx.databases.orders.auth.secret.store=secrets",
                        "halyx.databases.orders.auth.secret.name=orders/db",
                        "halyx.databases.orders.auth.secret.field=password"))
                .run(context -> {
                    ParameterStore secrets = context.getBean("secrets", ParameterStore.class);
                    secrets.put("orders/db", "{\"username\":\"app\",\"password\":\"initial-secret\"}");
                    HikariDataSource dataSource = (HikariDataSource) context.getBean("orders", DataSource.class);
                    assertThat(query(dataSource, "SELECT 'before'")).isEqualTo("before");

                    // Rotate: the database first, then the secret (as a rotation function would).
                    execute(dataSource, "ALTER ROLE app WITH PASSWORD 'rotated-secret'");
                    secrets.put("orders/db", "{\"username\":\"app\",\"password\":\"rotated-secret\"}");
                    dataSource.getHikariPoolMXBean().softEvictConnections();

                    assertThat(query(dataSource, "SELECT 'after'")).isEqualTo("after");
                    execute(dataSource, "ALTER ROLE app WITH PASSWORD 'initial-secret'");
                });
    }

    @Test
    void bootsDataSourceAutoConfigurationBacksOffAndJdbcTemplateUsesHalyx() {
        Class<?> dataSourceAutoConfiguration = bootClass(
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration");
        Class<?> jdbcTemplateAutoConfiguration = bootClass(
                "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration",
                "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration");
        assertThat(HalyxAutoConfiguration.class.getAnnotation(AutoConfiguration.class).beforeName())
                .contains(dataSourceAutoConfiguration.getName());

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(jdbcTemplateAutoConfiguration, dataSourceAutoConfiguration,
                        HalyxAutoConfiguration.class))
                .withPropertyValues(properties("halyx.databases.orders.auth.password=initial-secret",
                        // Boot would build its own pool from this if it didn't back off.
                        "spring.datasource.url=jdbc:postgresql://boot-should-not-use-this.invalid/x"))
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context.getBean(DataSource.class)).isSameAs(context.getBean("orders"));
                    JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                    assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("orders");
                });
    }

    private static Class<?> bootClass(String boot3, String boot4) {
        try {
            return Class.forName(boot3);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(boot4);
            } catch (ClassNotFoundException again) {
                throw new IllegalStateException("Neither " + boot3 + " nor " + boot4, again);
            }
        }
    }

    @Test
    void missingSettingsFailAtStartupWithThePropertyNamed() {
        runner.withPropertyValues(properties()).run(context ->
                assertThat(context).getFailure()
                        .hasStackTraceContaining("halyx.databases.orders.auth.password"));
        runner.withPropertyValues(properties("halyx.databases.orders.auth.secret.store=nope",
                "halyx.databases.orders.auth.secret.name=x")).run(context ->
                assertThat(context).getFailure()
                        .hasStackTraceContaining("halyx.databases.orders.auth.secret.store"));
    }

    private static String query(DataSource dataSource, String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
