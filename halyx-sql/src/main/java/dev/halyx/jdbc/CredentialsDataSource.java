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

import dev.halyx.sql.spi.SqlCredentialsSource;
import dev.halyx.sql.spi.SqlLogin;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * An unpooled data source that logs every new connection in with the current credentials, so a pool on
 * top of it picks up refreshed tokens and rotated passwords for each physical connection it opens.
 */
final class CredentialsDataSource implements DataSource {

    private final String url;
    private final Map<String, String> properties;
    private final SqlCredentialsSource credentials;
    private volatile Driver driver;
    private int loginTimeout;
    private PrintWriter logWriter;

    CredentialsDataSource(String url, Map<String, String> properties, SqlCredentialsSource credentials) {
        this.url = Objects.requireNonNull(url, "url");
        this.properties = Map.copyOf(properties);
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    @Override
    public Connection getConnection() throws SQLException {
        SqlLogin login = credentials.login();
        Properties props = new Properties();
        props.putAll(properties);
        if (login.username() != null) {
            props.setProperty("user", login.username());
        }
        if (login.password() != null) {
            props.setProperty("password", login.password());
        }
        props.putAll(login.connectionProperties());
        Connection connection = driver().connect(url, props);
        if (connection == null) {
            throw new SQLException("The JDBC driver does not accept " + url);
        }
        return connection;
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("Credentials come from the configured SqlAuth");
    }

    private Driver driver() throws SQLException {
        Driver d = driver;
        if (d == null) {
            try {
                d = DriverManager.getDriver(url);
            } catch (SQLException e) {
                throw new SQLException("No JDBC driver for " + url.substring(0, url.indexOf(':', 5) + 1)
                        + " URLs; add the driver to the classpath (org.postgresql:postgresql, "
                        + "com.mysql:mysql-connector-j or com.microsoft.sqlserver:mssql-jdbc)", e);
            }
            driver = d;
        }
        return d;
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("Not a wrapper for " + type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }
}
