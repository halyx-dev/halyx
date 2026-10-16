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
package dev.halyx.r2dbc;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.spi.CloudProvider;
import dev.halyx.spi.ProviderConnection;
import dev.halyx.sql.PoolSettings;
import dev.halyx.sql.SqlAuth;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEndpoint;
import dev.halyx.sql.SqlEngine;
import dev.halyx.sql.spi.SqlLogin;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.Connection;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pooled R2DBC connections on PostgreSQL and MySQL in Docker, logging in through a credentials source. */
@Testcontainers(disabledWithoutDocker = true)
class R2dbcDatabasesIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    /** A provider that connects to the config's host with its password, as every cloud provider does. */
    static final class EndpointProvider implements CloudProvider {
        @Override
        public String id() {
            return "endpoint-test";
        }

        @Override
        public ProviderConnection connect(ProviderConfig config) {
            return new ProviderConnection() {
                @Override
                public SqlEndpoint sqlEndpoint(SqlConfig sql) {
                    SqlAuth.Password password = (SqlAuth.Password) sql.auth();
                    return new SqlEndpoint(sql.engine(), sql.host().orElseThrow(), sql.port(), sql.database(),
                            () -> SqlLogin.password(password.username(), password.password().get()), false);
                }

                @Override
                public void close() {
                }
            };
        }
    }

    /** Disposes of the pool at the end of a try block: R2DBC's close() is reactive, not AutoCloseable. */
    record Pooled(ConnectionPool pool) implements AutoCloseable {
        @Override
        public void close() {
            pool.dispose();
        }
    }

    abstract static class Engine {

        abstract GenericContainer<?> container();

        abstract SqlEngine engine();

        abstract int containerPort();

        abstract String alterPassword(String newPassword);

        SqlConfig config(String password, int poolSize) {
            return SqlConfig.builder(engine()).name("r2dbc-" + engine().name().toLowerCase())
                    .host(container().getHost()).port(container().getMappedPort(containerPort())).database("app")
                    .auth(SqlAuth.password("app", password))
                    .pool(new PoolSettings(poolSize, 0, Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5)))
                    .build();
        }

        SqlEndpoint endpoint(java.util.function.Supplier<SqlLogin> logins, boolean tls) {
            return new SqlEndpoint(engine(), container().getHost(), container().getMappedPort(containerPort()), "app",
                    logins::get, tls);
        }

        static Object one(ConnectionPool pool, String sql) {
            return Mono.usingWhen(pool.create(), c -> Mono.from(c.createStatement(sql).execute())
                            .flatMap(r -> Mono.from(r.map((row, meta) -> row.get(0)))), Connection::close)
                    .block(WAIT);
        }

        @Test
        void passwordLoginThroughAProvider() {
            try (Halyx halyx = Halyx.builder().plugin(new EndpointProvider())
                    .register("db", ProviderConfig.of("endpoint-test")).build();
                 Pooled pooled = new Pooled(R2dbcDatabases.connectionFactory(halyx.provider("db"),
                         config("initial-secret", 2)))) {
                ConnectionPool pool = pooled.pool();
                assertThat(one(pool, "SELECT 1").toString()).isEqualTo("1");
                assertThat(pool.getMetadata().getName()).isNotBlank();
            }
        }

        @Test
        void aRotatedPasswordIsUsedForNewConnections() {
            AtomicReference<String> secret = new AtomicReference<>("initial-secret");
            SqlConfig config = config("unused", 2);
            try (Pooled pooled = new Pooled(R2dbcDatabases.connectionFactory(
                    endpoint(() -> SqlLogin.password("app", secret.get()), false), config))) {
                ConnectionPool pool = pooled.pool();
                Connection held = Mono.from(pool.create()).block(WAIT);
                one(pool, alterPassword("rotated-secret"));
                secret.set("rotated-secret");

                // The held connection stays logged in; a new physical connection uses the new password.
                Connection fresh = Mono.from(pool.create()).block(WAIT);
                assertThat(Mono.from(fresh.validate(io.r2dbc.spi.ValidationDepth.REMOTE)).block(WAIT)).isTrue();
                assertThat(Mono.from(held.validate(io.r2dbc.spi.ValidationDepth.REMOTE)).block(WAIT)).isTrue();
                Mono.from(held.close()).block(WAIT);
                Mono.from(fresh.close()).block(WAIT);
                one(pool, alterPassword("initial-secret"));
            }
        }

        @Test
        void expiringLoginsAreReusedAcrossABurstOfConnections() {
            AtomicInteger logins = new AtomicInteger();
            try (Pooled pooled = new Pooled(R2dbcDatabases.connectionFactory(endpoint(() -> {
                logins.incrementAndGet();
                return SqlLogin.token("app", "initial-secret", Instant.now().plus(Duration.ofMinutes(15)));
            }, false), config("unused", 5)))) {
                ConnectionPool pool = pooled.pool();
                List<Connection> opened = Flux.range(0, 5).flatMap(i -> pool.create()).collectList().block(WAIT);
                assertThat(opened).hasSize(5);
                Flux.fromIterable(opened).flatMap(Connection::close).blockLast(WAIT);
            }
            assertThat(logins.get()).isEqualTo(1);
        }

        @Test
        void aRejectedLoginFailsTheConnection() {
            try (Pooled pooled = new Pooled(R2dbcDatabases.connectionFactory(
                    endpoint(() -> SqlLogin.password("app", "wrong"), false), config("unused", 1)))) {
                ConnectionPool pool = pooled.pool();
                assertThatThrownBy(() -> one(pool, "SELECT 1")).isInstanceOf(RuntimeException.class);
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

        @Test
        void tlsIsRequiredForCloudLogins() {
            // This server has no TLS, so a login that requires it must not fall back to plaintext.
            try (Pooled pooled = new Pooled(R2dbcDatabases.connectionFactory(
                    endpoint(() -> SqlLogin.password("app", "initial-secret"), true), config("unused", 1)))) {
                ConnectionPool pool = pooled.pool();
                assertThatThrownBy(() -> one(pool, "SELECT 1")).isInstanceOf(RuntimeException.class);
            }
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

        @Test
        void tlsIsUsedForCloudLogins() {
            // MySQL generates a certificate at startup, so the required TLS session is established.
            try (Pooled pooled = new Pooled(R2dbcDatabases.connectionFactory(
                    endpoint(() -> SqlLogin.password("app", "initial-secret"), true), config("unused", 1)))) {
                ConnectionPool pool = pooled.pool();
                Object cipher = Mono.usingWhen(pool.create(),
                        c -> Mono.from(c.createStatement("SHOW SESSION STATUS LIKE 'Ssl_cipher'").execute())
                                .flatMap(r -> Mono.from(r.map((row, meta) -> row.get(1, String.class)))),
                        Connection::close).block(WAIT);
                assertThat(cipher).asString().isNotBlank();
            }
        }
    }

    @Test
    void sqlServerTokensAreRefused() {
        SqlEndpoint endpoint = new SqlEndpoint(SqlEngine.SQLSERVER, "localhost", 1433, "app",
                () -> SqlLogin.token("app", "token", Instant.now().plusSeconds(600)), true);
        // The driver is optional, so check the refusal only when it is on the test classpath.
        List<Throwable> failures = new ArrayList<>();
        try {
            Drivers.forEndpoint(endpoint).apply(endpoint.credentials().login());
        } catch (RuntimeException e) {
            failures.add(e);
        }
        assertThat(failures).singleElement().satisfies(e -> assertThat(e.getMessage()).containsAnyOf(
                "passwords only", "io.r2dbc:r2dbc-mssql"));
    }
}
