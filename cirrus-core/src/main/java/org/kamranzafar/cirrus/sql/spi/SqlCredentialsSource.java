package org.kamranzafar.cirrus.sql.spi;

/** Produces the login for a new physical connection: a password, or a freshly issued cloud token. */
@FunctionalInterface
public interface SqlCredentialsSource {

    SqlLogin login();
}
