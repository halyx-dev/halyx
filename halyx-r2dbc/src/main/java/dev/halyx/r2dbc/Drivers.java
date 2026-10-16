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

import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.sql.SqlEndpoint;
import dev.halyx.sql.spi.SqlLogin;
import io.asyncer.r2dbc.mysql.MySqlConnectionConfiguration;
import io.asyncer.r2dbc.mysql.MySqlConnectionFactory;
import io.asyncer.r2dbc.mysql.constant.SslMode;
import io.r2dbc.mssql.MssqlConnectionConfiguration;
import io.r2dbc.mssql.MssqlConnectionFactory;
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.postgresql.client.SSLMode;
import io.r2dbc.spi.ConnectionFactory;

import java.util.function.Function;

/**
 * The driver's connection factory for one login. Each engine's driver is referenced only from its own method,
 * so applications need only the driver they use.
 */
final class Drivers {

    private Drivers() {
    }

    static Function<SqlLogin, ConnectionFactory> forEndpoint(SqlEndpoint endpoint) {
        return switch (endpoint.engine()) {
            case POSTGRESQL -> {
                require("io.r2dbc.postgresql.PostgresqlConnectionFactory", "org.postgresql:r2dbc-postgresql");
                yield login -> postgresql(endpoint, login);
            }
            case MYSQL -> {
                require("io.asyncer.r2dbc.mysql.MySqlConnectionFactory", "io.asyncer:r2dbc-mysql");
                yield login -> mysql(endpoint, login);
            }
            case SQLSERVER -> {
                require("io.r2dbc.mssql.MssqlConnectionFactory", "io.r2dbc:r2dbc-mssql");
                yield login -> sqlServer(endpoint, login);
            }
        };
    }

    private static ConnectionFactory postgresql(SqlEndpoint endpoint, SqlLogin login) {
        PostgresqlConnectionConfiguration.Builder config = PostgresqlConnectionConfiguration.builder()
                .host(endpoint.host())
                .port(endpoint.port())
                .database(endpoint.database())
                .username(login.username());
        if (login.password() != null) {
            config.password(login.password());
        }
        if (endpoint.tlsRequired()) {
            config.sslMode(SSLMode.REQUIRE);
        }
        return new PostgresqlConnectionFactory(config.build());
    }

    private static ConnectionFactory mysql(SqlEndpoint endpoint, SqlLogin login) {
        MySqlConnectionConfiguration.Builder config = MySqlConnectionConfiguration.builder()
                .host(endpoint.host())
                .port(endpoint.port())
                .database(endpoint.database())
                .user(login.username());
        if (login.password() != null) {
            config.password(login.password());
        }
        if (endpoint.tlsRequired()) {
            config.sslMode(SslMode.REQUIRED);
        }
        return MySqlConnectionFactory.from(config.build());
    }

    private static ConnectionFactory sqlServer(SqlEndpoint endpoint, SqlLogin login) {
        if (login.expiresAt() != null || !login.connectionProperties().isEmpty()) {
            throw new UnsupportedFeatureException("The SQL Server R2DBC driver takes passwords only, not access "
                    + "tokens; use password auth, or JDBC for Entra ID login", null, null, null);
        }
        MssqlConnectionConfiguration.Builder config = MssqlConnectionConfiguration.builder()
                .host(endpoint.host())
                .port(endpoint.port())
                .database(endpoint.database())
                .username(login.username())
                .password(login.password());
        if (endpoint.tlsRequired()) {
            config.enableSsl();
        }
        return new MssqlConnectionFactory(config.build());
    }

    private static void require(String driverClass, String artifact) {
        try {
            Class.forName(driverClass, false, Drivers.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("R2DBC for this database needs the driver " + artifact
                    + " on the classpath", e);
        }
    }
}
