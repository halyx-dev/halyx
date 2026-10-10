package org.kamranzafar.cirrus.sql;

import javax.sql.DataSource;

/**
 * A pooled database connection source. Hand {@link #dataSource()} to JDBC code, JPA, jOOQ or Spring.
 * Closing it closes the pool; the provider connection closes it too when the Cirrus context closes.
 */
public interface Database extends AutoCloseable {

    SqlConfig config();

    /** The pooled data source; with IAM auth, each new physical connection logs in with a current token. */
    DataSource dataSource();

    /** True if a connection can be borrowed and is valid within {@code timeoutSeconds}. */
    boolean isHealthy(int timeoutSeconds);

    @Override
    void close();
}
