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
package dev.halyx.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import dev.halyx.sql.Database;
import dev.halyx.sql.PoolSettings;
import dev.halyx.sql.SqlAuth;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEngine;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pools against real PostgreSQL and MySQL servers, with password rotation through a supplier. */
@Testcontainers(disabledWithoutDocker = true)
class DatabaseIT {

    abstract static class Engine {

        private final JdbcDatabases databases = new JdbcDatabases();
        final AtomicReference<String> password = new AtomicReference<>("initial-secret");
        final AtomicInteger logins = new AtomicInteger();

        abstract GenericContainer<?> container();

        abstract SqlEngine engine();

        abstract int containerPort();

        abstract String alterPassword(String newPassword);

        Database database() {
            SqlConfig config = SqlConfig.builder(engine())
                    .name("it-" + engine().name().toLowerCase())
                    .host(container().getHost())
                    .port(container().getMappedPort(containerPort()))
                    .database("app")
                    .auth(SqlAuth.password("app", () -> {
                        logins.incrementAndGet();
                        return password.get();
                    }))
                    .jdbcProperty(engine() == SqlEngine.MYSQL ? "allowPublicKeyRetrieval" : "ApplicationName", "true")
                    .pool(new PoolSettings(4, 0, Duration.ofSeconds(5), Duration.ofMinutes(1), Duration.ofMinutes(5)))
                    .build();
            return databases.create(config, config.jdbcUrl(), JdbcDatabases.passwordSource((SqlAuth.Password) config.auth()),
                    java.util.Map.of());
        }

        @AfterEach
        void closePools() {
            databases.close();
        }

        @Test
        void poolsConnectionsAndRunsQueries() throws SQLException {
            try (Database database = database(); Connection c = database.dataSource().getConnection();
                 Statement s = c.createStatement(); var rs = s.executeQuery("SELECT 40 + 2")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(42);
                assertThat(database.isHealthy(2)).isTrue();
            }
        }

        @Test
        void newConnectionsUseTheRotatedPassword() throws SQLException {
            Database database = database();
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
                s.execute(alterPassword("rotated-secret"));
            }
            int before = logins.get();

            // The secret store now hands out the new password; replace the pooled connections.
            password.set("rotated-secret");
            ((HikariDataSource) database.dataSource()).getHikariPoolMXBean().softEvictConnections();

            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
                assertThat(s.execute("SELECT 1")).isTrue();
            }
            assertThat(logins.get()).isGreaterThan(before);

            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
                s.execute(alterPassword("initial-secret")); // restore for other tests
            }
            password.set("initial-secret");
        }

        @Test
        void wrongPasswordFailsToConnect() {
            password.set("not-the-password");
            try (Database database = database()) {
                assertThatThrownBy(() -> database.dataSource().getConnection().close()).isInstanceOf(SQLException.class);
                assertThat(database.isHealthy(1)).isFalse();
            } finally {
                password.set("initial-secret");
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Postgres extends Engine {

        @Container
        final GenericContainer<?> postgres = new GenericContainer<>("postgres:17-alpine")
                .withEnv("POSTGRES_USER", "app")
                .withEnv("POSTGRES_PASSWORD", "initial-secret")
                .withEnv("POSTGRES_DB", "app")
                .withExposedPorts(5432)
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));

        @Override
        GenericContainer<?> container() {
            return postgres;
        }

        @Override
        SqlEngine engine() {
            return SqlEngine.POSTGRESQL;
        }

        @Override
        int containerPort() {
            return 5432;
        }

        @Override
        String alterPassword(String newPassword) {
            return "ALTER ROLE app WITH PASSWORD '" + newPassword + "'";
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MySql extends Engine {

        @Container
        final GenericContainer<?> mysql = new GenericContainer<>("mysql:8.4")
                .withEnv("MYSQL_ROOT_PASSWORD", "root-secret")
                .withEnv("MYSQL_USER", "app")
                .withEnv("MYSQL_PASSWORD", "initial-secret")
                .withEnv("MYSQL_DATABASE", "app")
                .withExposedPorts(3306)
                .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306.*", 1))
                .withStartupTimeout(Duration.ofMinutes(3));

        @Override
        GenericContainer<?> container() {
            return mysql;
        }

        @Override
        SqlEngine engine() {
            return SqlEngine.MYSQL;
        }

        @Override
        int containerPort() {
            return 3306;
        }

        @Override
        String alterPassword(String newPassword) {
            return "ALTER USER 'app'@'%' IDENTIFIED BY '" + newPassword + "'";
        }
    }
}
