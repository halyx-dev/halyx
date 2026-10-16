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
package dev.halyx.aws;

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
import dev.halyx.aws.rds.RdsIamTokens;
import dev.halyx.CloudContext;
import dev.halyx.config.ProviderConfig;
import dev.halyx.r2dbc.R2dbcDatabases;
import dev.halyx.sql.Database;
import dev.halyx.sql.PoolSettings;
import dev.halyx.sql.SqlAuth;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEngine;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL databases against real RDS (tier 2): RDS IAM token login and password login, both over TLS, for
 * PostgreSQL and MySQL. Runs only with {@code -Dhalyx.real.aws.sql=true}, normally through
 * {@code TIER=2 scripts/aws-real-cloud.sh}, which creates the instances with Terraform. The master password
 * comes from the environment (HALYX_RDS_MASTER_PASSWORD), never from a system property.
 */
@EnabledIfSystemProperty(named = "halyx.real.aws.sql", matches = "true")
class RealAwsSqlIT {

    /** Database user that logs in with IAM tokens; the caller's IAM policy grants rds-db:connect for it. */
    static final String IAM_USER = "halyx_iam";
    /** Database user that exists but isn't enabled for IAM: a token must not log it in. */
    static final String NON_IAM_USER = "halyx_password_only";

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("aws", ProviderConfig.of("aws")
                .region(System.getenv().getOrDefault("AWS_REGION", "ap-southeast-2"))).build();
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

        /** Statements, run as the master user, that create {@link #IAM_USER} and {@link #NON_IAM_USER}. */
        abstract List<String> createUsers();

        /** True when the current connection is encrypted. */
        abstract boolean tls(Statement s) throws SQLException;

        /** The user the current connection is logged in as, without any host part. */
        abstract String currentUser(Statement s) throws SQLException;

        /** A query whose first column is the current user, without any host part (for R2DBC). */
        abstract String userQuery();

        /** A query whose first column is non-empty, and not false, when the connection is encrypted (for R2DBC). */
        abstract String tlsQuery();

        CloudContext aws() {
            return halyx.provider("aws");
        }

        Database database(SqlAuth auth) {
            Database database = aws().database(config(auth));
            open.add(database);
            return database;
        }

        SqlConfig config(SqlAuth auth) {
            SqlConfig.Builder config = SqlConfig.builder(engine())
                    .name("rds-" + engine().name().toLowerCase())
                    .host(host())
                    .port(port())
                    .database("app")
                    .auth(auth)
                    .pool(new PoolSettings(4, 0, Duration.ofSeconds(10), Duration.ofMinutes(1), Duration.ofMinutes(5)));
            if (auth instanceof SqlAuth.Password) {
                String[] tls = engine().tlsProperty(); // IAM login enforces TLS itself; ask for it here too
                config.jdbcProperty(tls[0], tls[1]);
            }
            return config.build();
        }

        @Test
        void r2dbcIamLoginRunsQueriesOverTls() {
            ConnectionPool pool = R2dbcDatabases.connectionFactory(aws(), config(SqlAuth.iam(IAM_USER)));
            try {
                assertThat(first(pool, userQuery())).isEqualTo(IAM_USER);
                assertThat(first(pool, tlsQuery())).as("TLS").isNotBlank().isNotEqualTo("false");
            } finally {
                pool.dispose();
            }
        }

        @Test
        void r2dbcTokenDoesNotLogInAUserNotEnabledForIam() {
            ConnectionPool pool = R2dbcDatabases.connectionFactory(aws(), config(SqlAuth.iam(NON_IAM_USER)));
            try {
                assertThatThrownBy(() -> first(pool, "SELECT 1")).isInstanceOf(RuntimeException.class);
            } finally {
                pool.dispose();
            }
        }

        Database master() {
            return database(SqlAuth.password(System.getProperty("halyx.real.aws.sql.master-user"),
                    System.getenv("HALYX_RDS_MASTER_PASSWORD")));
        }

        @BeforeAll
        void setUpUsers() throws SQLException {
            try (Database master = master(); Connection c = master.dataSource().getConnection();
                 Statement s = c.createStatement()) {
                for (String sql : createUsers()) {
                    s.execute(sql);
                }
            }
        }

        @AfterAll
        void closeDatabases() {
            open.forEach(Database::close);
        }

        /**
         * Login latency, connection by connection: plain JDBC with a password, plain JDBC with a token, then a
         * Halyx IAM pool. Runs first, so the first token login is the first one the new instance has seen.
         */
        @Test
        @Order(1)
        void loginLatency() throws SQLException {
            String url = SqlConfig.builder(engine()).host(host()).port(port()).database("app")
                    .auth(SqlAuth.iam(IAM_USER)).build().jdbcUrl();
            String[] tls = engine().tlsProperty();
            String master = System.getProperty("halyx.real.aws.sql.master-user");
            String region = System.getenv().getOrDefault("AWS_REGION", "ap-southeast-2");
            var tokens = new RdsIamTokens(host(), port(), IAM_USER, region, DefaultCredentialsProvider.builder().build());
            List<String> timings = new ArrayList<>();

            for (int i = 1; i <= 3; i++) {
                timings.add("jdbc password #" + i + ": " + connectMillis(url, tls, master,
                        System.getenv("HALYX_RDS_MASTER_PASSWORD")) + " ms");
            }
            long[] tokenMillis = new long[5];
            for (int i = 0; i < 5; i++) {
                long sign = System.nanoTime();
                String token = tokens.login().password();
                long signed = (System.nanoTime() - sign) / 1_000_000;
                tokenMillis[i] = connectMillis(url, tls, IAM_USER, token);
                timings.add("jdbc token #" + (i + 1) + ": " + tokenMillis[i] + " ms (signing " + signed + " ms)");
            }
            Database database = database(SqlAuth.iam(IAM_USER));
            HikariDataSource pool = (HikariDataSource) database.dataSource();
            for (int i = 1; i <= 3; i++) {
                long start = System.nanoTime();
                pool.getConnection().close();
                timings.add("halyx pool, new connection #" + i + ": " + (System.nanoTime() - start) / 1_000_000 + " ms");
                pool.getHikariPoolMXBean().softEvictConnections();
            }
            System.out.println("[login latency] " + engine() + "\n  " + String.join("\n  ", timings));

            // A warm token login must cost about what a password login does, not seconds.
            assertThat(tokenMillis[4]).as("warm token login, ms").isLessThan(3_000);
        }

        private static long connectMillis(String url, String[] tls, String user, String password) throws SQLException {
            Properties props = new Properties();
            props.setProperty(tls[0], tls[1]);
            props.setProperty("user", user);
            props.setProperty("password", password);
            long start = System.nanoTime();
            try (Connection c = DriverManager.getConnection(url, props)) {
                return (System.nanoTime() - start) / 1_000_000;
            }
        }

        @Test
        void passwordLoginRunsQueriesOverTls() throws SQLException {
            Database database = master();
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
                assertThat(currentUser(s)).isEqualTo(System.getProperty("halyx.real.aws.sql.master-user"));
                assertThat(tls(s)).as("TLS").isTrue();
            }
            assertThat(database.isHealthy(5)).isTrue();
        }

        @Test
        void iamTokenLoginRunsQueriesOverTls() throws SQLException {
            Database database = database(SqlAuth.iam(IAM_USER));
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 40 + 2")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(42);
                assertThat(currentUser(s)).isEqualTo(IAM_USER);
                assertThat(tls(s)).as("TLS").isTrue();
            }
            assertThat(database.isHealthy(5)).isTrue();
        }

        @Test
        void replacedConnectionsLogInAgainWithTokens() throws SQLException {
            Database database = database(SqlAuth.iam(IAM_USER));
            HikariDataSource pool = (HikariDataSource) database.dataSource();
            for (int round = 0; round < 3; round++) {
                List<Connection> held = new ArrayList<>();
                try {
                    for (int i = 0; i < 4; i++) { // the whole pool, so each round opens new connections
                        held.add(pool.getConnection());
                    }
                    for (Connection c : held) {
                        try (Statement s = c.createStatement()) {
                            assertThat(currentUser(s)).isEqualTo(IAM_USER);
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

        @Test
        void tokenDoesNotLogInAUserNotEnabledForIam() {
            Database database = database(SqlAuth.iam(NON_IAM_USER));
            assertThatThrownBy(() -> database.dataSource().getConnection().close()).isInstanceOf(SQLException.class);
            assertThat(database.isHealthy(2)).isFalse();
        }

        @Test
        void wrongPasswordDoesNotLogIn() {
            Database database = database(SqlAuth.password(System.getProperty("halyx.real.aws.sql.master-user"),
                    "not-the-password"));
            assertThatThrownBy(() -> database.dataSource().getConnection().close()).isInstanceOf(SQLException.class);
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
            return System.getProperty("halyx.real.aws.sql.postgres-host");
        }

        @Override
        int port() {
            return 5432;
        }

        @Override
        List<String> createUsers() {
            return List.of(
                    "DROP USER IF EXISTS " + IAM_USER,
                    "CREATE USER " + IAM_USER,
                    "GRANT rds_iam TO " + IAM_USER,
                    "DROP USER IF EXISTS " + NON_IAM_USER,
                    "CREATE USER " + NON_IAM_USER + " PASSWORD 'unused-" + Long.toHexString(System.nanoTime()) + "'");
        }

        @Override
        boolean tls(Statement s) throws SQLException {
            try (ResultSet rs = s.executeQuery("SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
                return rs.next() && rs.getBoolean(1);
            }
        }

        @Override
        String currentUser(Statement s) throws SQLException {
            try (ResultSet rs = s.executeQuery("SELECT current_user")) {
                rs.next();
                return rs.getString(1);
            }
        }

        @Override
        String userQuery() {
            return "SELECT current_user";
        }

        @Override
        String tlsQuery() {
            return "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()";
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class MySql extends Engine {

        @Override
        SqlEngine engine() {
            return SqlEngine.MYSQL;
        }

        @Override
        String host() {
            return System.getProperty("halyx.real.aws.sql.mysql-host");
        }

        @Override
        int port() {
            return 3306;
        }

        @Override
        List<String> createUsers() {
            return List.of(
                    "DROP USER IF EXISTS '" + IAM_USER + "'@'%'",
                    "CREATE USER '" + IAM_USER + "'@'%' IDENTIFIED WITH AWSAuthenticationPlugin AS 'RDS' REQUIRE SSL",
                    "GRANT SELECT ON app.* TO '" + IAM_USER + "'@'%'",
                    "DROP USER IF EXISTS '" + NON_IAM_USER + "'@'%'",
                    "CREATE USER '" + NON_IAM_USER + "'@'%' IDENTIFIED BY 'unused-" + Long.toHexString(System.nanoTime())
                            + "' REQUIRE SSL");
        }

        @Override
        boolean tls(Statement s) throws SQLException {
            try (ResultSet rs = s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_version'")) {
                return rs.next() && rs.getString(2) != null && !rs.getString(2).isEmpty();
            }
        }

        @Override
        String currentUser(Statement s) throws SQLException {
            try (ResultSet rs = s.executeQuery("SELECT CURRENT_USER()")) {
                rs.next();
                return rs.getString(1).replaceFirst("@.*$", "");
            }
        }

        @Override
        String userQuery() {
            return "SELECT SUBSTRING_INDEX(CURRENT_USER(), '@', 1)";
        }

        @Override
        String tlsQuery() {
            return "SELECT VARIABLE_VALUE FROM performance_schema.session_status WHERE VARIABLE_NAME = 'Ssl_version'";
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
