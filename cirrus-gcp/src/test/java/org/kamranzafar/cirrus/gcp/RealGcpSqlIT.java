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
package org.kamranzafar.cirrus.gcp;

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
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.sql.Database;
import org.kamranzafar.cirrus.sql.PoolSettings;
import org.kamranzafar.cirrus.sql.SqlAuth;
import org.kamranzafar.cirrus.sql.SqlConfig;
import org.kamranzafar.cirrus.sql.SqlEngine;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SQL databases against real Cloud SQL (tier 2): IAM database authentication and password login for
 * PostgreSQL and MySQL, both directly over TLS to the public IP and through the Cloud SQL Java connector.
 * Runs only with {@code -Dcirrus.real.gcp.sql=true}, normally through {@code TIER=2 scripts/gcp-real-cloud.sh}.
 * The tests run as the service account Application Default Credentials impersonate, which is also the IAM
 * database user. Passwords come from the environment, never from system properties.
 */
@EnabledIfSystemProperty(named = "cirrus.real.gcp.sql", matches = "true")
class RealGcpSqlIT {

    private static final String PROJECT = System.getProperty("cirrus.real.gcp.project");
    private static final String SERVICE_ACCOUNT = System.getProperty("cirrus.real.gcp.service-account");
    private static final String ADMIN_USER = System.getProperty("cirrus.real.gcp.sql.admin-user");
    /** A built-in (password) user: an IAM token must not log it in. */
    private static final String PASSWORD_ONLY_USER = "cirrus_password_only";

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder().register("gcp", ProviderConfig.of("gcp")
                .property(GcpProperties.PROJECT_ID, PROJECT)).build();
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
    }

    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    abstract static class Engine {

        private final List<Database> open = new ArrayList<>();

        abstract SqlEngine engine();

        abstract String host();

        abstract String instance();

        /** The IAM database user for the service account. */
        abstract String iamUser();

        abstract boolean tls(Statement s) throws SQLException;

        abstract String currentUser(Statement s) throws SQLException;

        CloudContext gcp() {
            return cirrus.provider("gcp");
        }

        /** A direct connection to the instance's public IP. */
        Database direct(SqlAuth auth) {
            return database(auth, b -> b.host(host()).port(engine() == SqlEngine.MYSQL ? 3306 : 5432));
        }

        /** A connection through the Cloud SQL Java connector: no host, no authorized network. */
        Database connector(SqlAuth auth) {
            return database(auth, b -> b.property(GcpProperties.SQL_INSTANCE, instance()));
        }

        private Database database(SqlAuth auth, Consumer<SqlConfig.Builder> where) {
            SqlConfig.Builder config = SqlConfig.builder(engine())
                    .name("cloudsql-" + engine().name().toLowerCase())
                    .database("app")
                    .auth(auth)
                    .pool(new PoolSettings(4, 0, Duration.ofSeconds(20), Duration.ofMinutes(1), Duration.ofMinutes(5)));
            where.accept(config);
            if (auth instanceof SqlAuth.Password && config.build().property(GcpProperties.SQL_INSTANCE).isEmpty()) {
                String[] tls = engine().tlsProperty(); // IAM login turns TLS on itself; ask for it here too
                config.jdbcProperty(tls[0], tls[1]);
            }
            Database database = gcp().database(config.build());
            open.add(database);
            return database;
        }

        static SqlAuth admin() {
            return SqlAuth.password(ADMIN_USER, System.getenv("CIRRUS_CLOUDSQL_ADMIN_PASSWORD"));
        }

        /** Statements, run once as the admin user, that let the IAM user use the {@code app} database. */
        List<String> grants() {
            return List.of();
        }

        @BeforeAll
        void grantTheIamUser() throws SQLException {
            if (grants().isEmpty()) {
                return;
            }
            try (Database admin = direct(admin()); Connection c = admin.dataSource().getConnection();
                 Statement s = c.createStatement()) {
                for (String sql : grants()) {
                    s.execute(sql);
                }
            }
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

        /** Login latency per connection, first on the new instance included; runs before the other tests. */
        @Test
        @Order(1)
        void loginLatency() throws SQLException {
            List<String> timings = new ArrayList<>();
            long warmToken = 0;
            for (var kind : List.of("direct password", "direct token", "connector token")) {
                Database database = switch (kind) {
                    case "direct password" -> direct(admin());
                    case "direct token" -> direct(SqlAuth.iam());
                    default -> connector(SqlAuth.iam());
                };
                HikariDataSource pool = (HikariDataSource) database.dataSource();
                for (int i = 1; i <= 3; i++) {
                    long millis = connectMillis(database);
                    timings.add(kind + " #" + i + ": " + millis + " ms");
                    if (kind.equals("direct token")) {
                        warmToken = millis;
                    }
                    pool.getHikariPoolMXBean().softEvictConnections();
                }
            }
            System.out.println("[login latency] Cloud SQL " + engine() + "\n  " + String.join("\n  ", timings));

            assertThat(warmToken).as("warm token login, ms").isLessThan(3_000);
        }

        @Test
        void passwordLoginRunsQueriesOverTls() throws SQLException {
            Database database = direct(admin());
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
                assertThat(currentUser(s)).isEqualTo(ADMIN_USER);
                assertThat(tls(s)).as("TLS").isTrue();
            }
            assertThat(database.isHealthy(5)).isTrue();
        }

        @Test
        void iamLoginWithDerivedUserRunsQueriesOverTls() throws SQLException {
            Database database = direct(SqlAuth.iam()); // the user comes from the service account
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT 40 + 2")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(42);
                assertThat(currentUser(s)).isEqualTo(iamUser());
                assertThat(tls(s)).as("TLS").isTrue();
            }
            assertThat(database.isHealthy(5)).isTrue();
        }

        @Test
        void iamLoginWithExplicitUser() throws SQLException {
            Database database = direct(SqlAuth.iam(iamUser()));
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
                assertThat(currentUser(s)).isEqualTo(iamUser());
            }
        }

        @Test
        void connectorIamLogin() throws SQLException {
            Database database = connector(SqlAuth.iam());
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
                assertThat(currentUser(s)).isEqualTo(iamUser());
            }
            assertThat(database.isHealthy(5)).isTrue();
        }

        @Test
        void connectorPasswordLogin() throws SQLException {
            Database database = connector(admin());
            try (Connection c = database.dataSource().getConnection(); Statement s = c.createStatement()) {
                assertThat(currentUser(s)).isEqualTo(ADMIN_USER);
            }
        }

        @Test
        void replacedConnectionsLogInAgainWithTokens() throws SQLException {
            Database database = direct(SqlAuth.iam());
            HikariDataSource pool = (HikariDataSource) database.dataSource();
            for (int round = 0; round < 3; round++) {
                List<Connection> held = new ArrayList<>();
                try {
                    for (int i = 0; i < 4; i++) { // the whole pool, so each round opens new connections
                        held.add(pool.getConnection());
                    }
                    for (Connection c : held) {
                        try (Statement s = c.createStatement()) {
                            assertThat(currentUser(s)).isEqualTo(iamUser());
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
            Database database = direct(SqlAuth.iam(PASSWORD_ONLY_USER));
            assertThatThrownBy(() -> database.dataSource().getConnection().close()).isInstanceOf(SQLException.class);
            assertThat(database.isHealthy(2)).isFalse();
        }

        @Test
        void wrongPasswordDoesNotLogIn() {
            Database database = direct(SqlAuth.password(ADMIN_USER, "not-the-password"));
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
            return System.getProperty("cirrus.real.gcp.sql.postgres-host");
        }

        @Override
        String instance() {
            return System.getProperty("cirrus.real.gcp.sql.postgres-instance");
        }

        @Override
        String iamUser() {
            return SERVICE_ACCOUNT.replaceFirst("\\.gserviceaccount\\.com$", "");
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
            return System.getProperty("cirrus.real.gcp.sql.mysql-host");
        }

        @Override
        String instance() {
            return System.getProperty("cirrus.real.gcp.sql.mysql-instance");
        }

        @Override
        String iamUser() {
            return SERVICE_ACCOUNT.substring(0, SERVICE_ACCOUNT.indexOf('@'));
        }

        /** MySQL IAM users start with no privileges, so they can't even select the database they connect to. */
        @Override
        List<String> grants() {
            return List.of("GRANT SELECT ON app.* TO '" + iamUser() + "'@'%'");
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
    }
}
