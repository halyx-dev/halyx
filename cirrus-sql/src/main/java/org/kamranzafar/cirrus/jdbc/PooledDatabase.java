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
package org.kamranzafar.cirrus.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import org.kamranzafar.cirrus.sql.Database;
import org.kamranzafar.cirrus.sql.PoolSettings;
import org.kamranzafar.cirrus.sql.SqlConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A HikariCP pool over a {@link CredentialsDataSource}. The pool starts lazily on first use, so an
 * application can start while its database is still unreachable.
 */
final class PooledDatabase implements Database {

    private final SqlConfig config;
    private final HikariDataSource pool;

    PooledDatabase(SqlConfig config, DataSource connections) {
        this.config = config;
        PoolSettings settings = config.pool();
        pool = new HikariDataSource();
        pool.setPoolName(config.name());
        pool.setDataSource(connections);
        pool.setMaximumPoolSize(settings.maxSize());
        pool.setMinimumIdle(settings.minIdle());
        pool.setConnectionTimeout(settings.connectionTimeout().toMillis());
        pool.setIdleTimeout(settings.idleTimeout().toMillis());
        pool.setMaxLifetime(settings.maxLifetime().toMillis());
    }

    @Override
    public SqlConfig config() {
        return config;
    }

    /** The Hikari pool itself, so frameworks that recognise it (Spring Boot's pool metrics) can. */
    @Override
    public DataSource dataSource() {
        return pool;
    }

    @Override
    public boolean isHealthy(int timeoutSeconds) {
        try (Connection connection = pool.getConnection()) {
            return connection.isValid(timeoutSeconds);
        } catch (SQLException | RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    @Override
    public String toString() {
        return "Database[" + config + "]";
    }
}
