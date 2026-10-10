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
