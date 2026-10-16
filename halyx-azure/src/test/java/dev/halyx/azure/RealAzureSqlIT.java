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
package dev.halyx.azure;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import dev.halyx.Halyx;
import dev.halyx.CloudContext;
import dev.halyx.config.ProviderConfig;
import dev.halyx.r2dbc.R2dbcDatabases;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Mono;
import dev.halyx.sql.Database;
import dev.halyx.sql.PoolSettings;
import dev.halyx.sql.SqlAuth;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEngine;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL databases against real Azure (tier 2): Microsoft Entra ID login to Azure Database for PostgreSQL
 * (flexible server) and Azure SQL Database, both with the test service principal as Entra admin. Runs only
 * with {@code -Dhalyx.real.azure.sql=true}, normally through {@code TIER=2 scripts/azure-real-cloud.sh}.
 * Credentials are {@code DefaultAzureCredential} (the principal from the environment); the PostgreSQL admin
 * password comes from the environment, never from a system property.
 */
@EnabledIfSystemProperty(named = "halyx.real.azure.sql", matches = "true")
class RealAzureSqlIT {

    /** The service principal's display name: its PostgreSQL role and its Azure SQL admin login. */
    private static final String PRINCIPAL = System.getProperty("halyx.real.azure.sql.principal-name", "halyx-test");
    /** PostgreSQL's password-authenticated admin role; Azure SQL has no SQL logins at all (Entra only). */
    private static final String PASSWORD_ADMIN = "halyx_admin";

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")).build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    abstract static class Engine {

        private final List<Database> open = new ArrayList<>();

        abstract SqlEngine engine();

        abstract String host();

        abstract int port();

        /** Entra login for the service principal. */
        abstract SqlAuth entra();

        abstract boolean tls(Statement s) throws SQLException;

        /** The login the session runs as. */
        abstract String currentLogin(Statement s) throws SQLException;

        CloudContext azure() {
            return halyx.provider("azure");
        }

        Database database(SqlAuth auth) {
            Database database = azure().database(config(auth));
            open.add(database);
            return database;
        }

        SqlConfig config(SqlAuth auth) {
            SqlConfig.Builder config = SqlConfig.builder(engine())
                    .name("azure-" + engine().name().toLowerCase())
                    .host(host())
                    .port(port())
                    .database("app")
                    .auth(auth)
                    .pool(new PoolSettings(4, 0, Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(5)));
            if (auth instanceof SqlAuth.Password) {
                String[] tls = engine().tlsProperty(); // Entra login turns TLS on itself; ask for it here too
                config.jdbcProperty(tls[0], tls[1]);
            }
            return config.build();
        }

        @AfterAll
        void closeDatabases() {
            open.forEach(Database::close);
        }

        private static long connectMillis(Database database) throws SQLException {
            long start = System.nanoTime();
            try (Connection c = database.dataSource().getConnection()) {
                return (System.nanoTime() - start) / 1_000_000;
            }
        }

        /** Login latency per new connection, the first on the new server included; runs before the others. */
        @Test
        @Order(1)
        void loginLatency() throws SQLException {
            Database database = database(entra());
            HikariDataSource pool = (HikariDataSource) database.dataSource();
            List<String> timings = new ArrayList<>();
            long warm = 0;
            for (int i = 1; i <= 4; i++) {
                warm = connectMillis(database);
                timings.add("entra token #" + i + ": " + warm + " ms");
                pool.getHikariPoolMXBean().softEvictConnections();
            }
            System.out.println("[login latency] Azure " + engine() + "\n  " + String.join("\n  ", timings));

            assertThat(warm).as("warm token login, ms").isLessThan(3_000);
        }

        @Test
        void entraLoginRunsQueriesOverTls() throws SQLException {
            Database database = database(entra());
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 40 + 2")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(42);
                String login = currentLogin(s);
                System.out.println("[azure sql] " + engine() + " session login: " + login);
                assertThat(login).isNotBlank();
                assertThat(tls(s)).as("TLS").isTrue();
            }
            assertThat(database.isHealthy(5)).isTrue();
        }

        @Test
        void replacedConnectionsLogInAgainWithTokens() throws SQLException {
            Database database = database(entra());
            HikariDataSource pool = (HikariDataSource) database.dataSource();
            for (int round = 0; round < 3; round++) {
                List<Connection> held = new ArrayList<>();
                try {
                    for (int i = 0; i < 4; i++) { // the whole pool, so each round opens new connections
                        held.add(pool.getConnection());
                    }
                    for (Connection c : held) {
                        try (Statement s = c.createStatement()) {
                            assertThat(currentLogin(s)).isNotBlank();
                        }
                    }
                } finally {
                    for (Connection c : held) {
                        c.close();
                    }
                }
                pool.getHikariPoolMXBean().softEvictConnections();
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Postgres extends Engine {

        @Override
        SqlEngine engine() {
            return SqlEngine.POSTGRESQL;
        }

        @Override
        String host() {
            return System.getProperty("halyx.real.azure.sql.postgres-host");
        }

        @Override
        int port() {
            return 5432;
        }

        @Override
        SqlAuth entra() {
            return SqlAuth.iam(PRINCIPAL);
        }

        @Override
        boolean tls(Statement s) throws SQLException {
            try (ResultSet rs = s.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
                return rs.next() && rs.getBoolean(1);
            }
        }

        @Override
        String currentLogin(Statement s) throws SQLException {
            try (ResultSet rs = s.executeQuery("SELECT current_user")) {
                rs.next();
                return rs.getString(1);
            }
        }

        @Test
        void r2dbcEntraLoginRunsQueriesOverTls() {
            ConnectionPool pool = R2dbcDatabases.connectionFactory(azure(), config(entra()));
            try {
                assertThat(first(pool, "SELECT current_user")).isEqualTo(PRINCIPAL);
                assertThat(first(pool, "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")).as("TLS")
                        .isEqualTo("true");
            } finally {
                pool.dispose();
            }
        }

        @Test
        void r2dbcTokenDoesNotLogInARoleThatIsNotAnEntraPrincipal() {
            ConnectionPool pool = R2dbcDatabases.connectionFactory(azure(), config(SqlAuth.iam(PASSWORD_ADMIN)));
            try {
                assertThatThrownBy(() -> first(pool, "SELECT 1")).isInstanceOf(RuntimeException.class);
            } finally {
                pool.dispose();
            }
        }

        @Test
        void entraLoginIsThePrincipalsRole() throws SQLException {
            try (Connection c = database(entra()).dataSource().getConnection(); Statement s = c.createStatement()) {
                assertThat(currentLogin(s)).isEqualTo(PRINCIPAL);
            }
        }

        @Test
        void passwordLoginRunsQueriesOverTls() throws SQLException {
            Database database = database(SqlAuth.password(PASSWORD_ADMIN, System.getenv("HALYX_AZURE_PG_ADMIN_PASSWORD")));
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
                assertThat(currentLogin(s)).isEqualTo(PASSWORD_ADMIN);
                assertThat(tls(s)).as("TLS").isTrue();
            }
        }

        @Test
        void tokenDoesNotLogInARoleThatIsNotAnEntraPrincipal() {
            Database database = database(SqlAuth.iam(PASSWORD_ADMIN));
            assertThatThrownBy(() -> database.dataSource().getConnection().close()).isInstanceOf(SQLException.class);
            assertThat(database.isHealthy(2)).isFalse();
        }

        @Test
        void wrongPasswordDoesNotLogIn() {
            Database database = database(SqlAuth.password(PASSWORD_ADMIN, "not-the-password"));
            assertThatThrownBy(() -> database.dataSource().getConnection().close()).isInstanceOf(SQLException.class);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SqlServer extends Engine {

        @Override
        SqlEngine engine() {
            return SqlEngine.SQLSERVER;
        }

        @Override
        String host() {
            return System.getProperty("halyx.real.azure.sql.sql-host");
        }

        @Override
        int port() {
            return 1433;
        }

        /** Azure SQL takes the token as the driver's accessToken; there is no username. */
        @Override
        SqlAuth entra() {
            return SqlAuth.iam();
        }

        @Override
        boolean tls(Statement s) throws SQLException {
            try (ResultSet rs = s.executeQuery("SELECT encrypt_option FROM sys.dm_exec_connections WHERE session_id = @@SPID")) {
                return rs.next() && "TRUE".equalsIgnoreCase(rs.getString(1));
            }
        }

        @Override
        String currentLogin(Statement s) throws SQLException {
            try (ResultSet rs = s.executeQuery("SELECT SUSER_SNAME()")) {
                rs.next();
                return rs.getString(1);
            }
        }

        @Test
        void sqlPasswordLoginIsRefusedOnAnEntraOnlyServer() {
            Database database = database(SqlAuth.password(PASSWORD_ADMIN, "any-password-1A!"));
            assertThatThrownBy(() -> database.dataSource().getConnection().close()).isInstanceOf(SQLException.class);
            assertThat(database.isHealthy(2)).isFalse();
        }
    }

    /** The first column of the query's first row, as a string, over a pooled R2DBC connection. */
    static String first(ConnectionFactory pool, String sql) {
        return Mono.usingWhen(pool.create(),
                        c -> Mono.from(c.createStatement(sql).execute())
                                .flatMap(r -> Mono.from(r.map((row, meta) -> String.valueOf(row.get(0))))),
                        io.r2dbc.spi.Connection::close)
                .block(Duration.ofSeconds(60));
    }
}
