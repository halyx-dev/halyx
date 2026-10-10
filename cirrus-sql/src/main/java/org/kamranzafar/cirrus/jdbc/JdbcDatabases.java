package org.kamranzafar.cirrus.jdbc;

import org.kamranzafar.cirrus.sql.Database;
import org.kamranzafar.cirrus.sql.SqlAuth;
import org.kamranzafar.cirrus.sql.SqlConfig;
import org.kamranzafar.cirrus.sql.SqlEngine;
import org.kamranzafar.cirrus.sql.spi.SqlCredentialsSource;
import org.kamranzafar.cirrus.sql.spi.SqlLogin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds pooled databases for provider connections, and tracks them so the connection can close them.
 * One instance per provider connection.
 */
public final class JdbcDatabases implements AutoCloseable {

    private final List<Database> open = new ArrayList<>();

    /**
     * A pool for {@code url} whose connections log in through {@code credentials}. Properties are applied
     * in order: {@code defaults} (what the provider needs, such as TLS for IAM), then the config's own JDBC
     * properties, which win.
     */
    public synchronized Database create(SqlConfig config, String url, SqlCredentialsSource credentials,
                                        Map<String, String> defaults) {
        Map<String, String> properties = new LinkedHashMap<>(defaults);
        properties.putAll(config.jdbcProperties());
        Database database = new PooledDatabase(config,
                new CredentialsDataSource(url, properties, new CachingCredentialsSource(credentials)));
        open.add(database);
        return database;
    }

    /** A pool with password auth at the config's host: what every provider does for {@link SqlAuth.Password}. */
    public Database createWithPassword(SqlConfig config) {
        if (!(config.auth() instanceof SqlAuth.Password password)) {
            throw new IllegalArgumentException("Not password auth: " + config.auth());
        }
        return create(config, config.jdbcUrl(), passwordSource(password), Map.of());
    }

    public static SqlCredentialsSource passwordSource(SqlAuth.Password password) {
        return () -> SqlLogin.password(password.username(), password.password().get());
    }

    /** The JDBC property requiring TLS, which every cloud's IAM login needs. */
    public static Map<String, String> requireTls(SqlEngine engine) {
        String[] tls = engine.tlsProperty();
        return Map.of(tls[0], tls[1]);
    }

    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        for (Database database : open) {
            try {
                database.close();
            } catch (RuntimeException e) {
                failure = failure == null ? e : failure;
            }
        }
        open.clear();
        if (failure != null) {
            throw failure;
        }
    }
}
