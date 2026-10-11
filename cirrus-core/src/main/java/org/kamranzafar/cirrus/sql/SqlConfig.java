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
package org.kamranzafar.cirrus.sql;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A database to connect to: engine, endpoint, login and pool.
 *
 * <pre>{@code
 * Database orders = cirrus.provider("aws").database(SqlConfig.builder(SqlEngine.POSTGRESQL)
 *         .host("orders.cluster-abc.eu-west-1.rds.amazonaws.com")
 *         .database("orders")
 *         .auth(SqlAuth.iam("orders_app"))
 *         .build());
 * DataSource dataSource = orders.dataSource();
 * }</pre>
 */
public final class SqlConfig {

    private final String name;
    private final SqlEngine engine;
    private final String host;
    private final int port;
    private final String database;
    private final SqlAuth auth;
    private final Map<String, String> jdbcProperties;
    private final Map<String, String> properties;
    private final PoolSettings pool;

    private SqlConfig(Builder b) {
        this.name = b.name;
        this.engine = b.engine;
        this.host = b.host;
        this.port = b.port == 0 ? b.engine.defaultPort() : b.port;
        this.database = b.database;
        this.auth = b.auth;
        this.jdbcProperties = Map.copyOf(b.jdbcProperties);
        this.properties = Map.copyOf(b.properties);
        this.pool = b.pool;
    }

    public static Builder builder(SqlEngine engine) {
        return new Builder(engine);
    }

    /** Pool name, used in metrics and thread names. */
    public String name() {
        return name;
    }

    public SqlEngine engine() {
        return engine;
    }

    /** Database host; empty when a provider connects some other way (the Cloud SQL connector). */
    public Optional<String> host() {
        return Optional.ofNullable(host);
    }

    public int port() {
        return port;
    }

    public String database() {
        return database;
    }

    public SqlAuth auth() {
        return auth;
    }

    /** Passed to the JDBC driver as connection properties, overriding Cirrus' defaults (such as TLS mode). */
    public Map<String, String> jdbcProperties() {
        return jdbcProperties;
    }

    /** Provider-specific settings, such as {@code gcp.sql.instance}. */
    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, String> properties() {
        return properties;
    }

    public PoolSettings pool() {
        return pool;
    }

    /** The JDBC URL for the host, port and database. */
    public String jdbcUrl() {
        return engine.jdbcUrl(host().orElseThrow(() -> new IllegalStateException("No host configured")), port, database);
    }

    @Override
    public String toString() {
        return "SqlConfig[name=" + name + ", engine=" + engine + ", host=" + host + ", port=" + port
                + ", database=" + database + ", auth=" + auth + "]";
    }

    public static final class Builder {
        private final SqlEngine engine;
        private String name = "cirrus-sql";
        private String host;
        private int port;
        private String database;
        private SqlAuth auth;
        private final Map<String, String> jdbcProperties = new LinkedHashMap<>();
        private final Map<String, String> properties = new LinkedHashMap<>();
        private PoolSettings pool = PoolSettings.DEFAULTS;

        private Builder(SqlEngine engine) {
            this.engine = Objects.requireNonNull(engine, "engine");
        }

        public Builder name(String name) {
            this.name = Objects.requireNonNull(name, "name");
            return this;
        }

        public Builder host(String host) {
            this.host = Objects.requireNonNull(host, "host");
            return this;
        }

        public Builder port(int port) {
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            this.port = port;
            return this;
        }

        public Builder database(String database) {
            this.database = Objects.requireNonNull(database, "database");
            return this;
        }

        public Builder auth(SqlAuth auth) {
            this.auth = Objects.requireNonNull(auth, "auth");
            return this;
        }

        public Builder jdbcProperty(String key, String value) {
            jdbcProperties.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder property(String key, String value) {
            properties.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder pool(PoolSettings pool) {
            this.pool = Objects.requireNonNull(pool, "pool");
            return this;
        }

        public SqlConfig build() {
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(auth, "auth");
            return new SqlConfig(this);
        }
    }
}
