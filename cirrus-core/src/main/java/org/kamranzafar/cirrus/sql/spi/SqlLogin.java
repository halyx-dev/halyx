package org.kamranzafar.cirrus.sql.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Credentials for one new connection.
 *
 * @param username             JDBC {@code user}, or null when the login carries none (Azure SQL access tokens)
 * @param password             JDBC {@code password} (a password or an IAM token), or null
 * @param connectionProperties extra JDBC properties, such as SQL Server's {@code accessToken}
 * @param expiresAt            when the credentials stop working for new connections; null if they don't
 */
public record SqlLogin(String username, String password, Map<String, String> connectionProperties, Instant expiresAt) {

    public SqlLogin {
        connectionProperties = Map.copyOf(Objects.requireNonNull(connectionProperties, "connectionProperties"));
    }

    public static SqlLogin password(String username, String password) {
        return new SqlLogin(username, password, Map.of(), null);
    }

    public static SqlLogin token(String username, String token, Instant expiresAt) {
        return new SqlLogin(username, token, Map.of(), Objects.requireNonNull(expiresAt, "expiresAt"));
    }

    public Optional<Instant> expiry() {
        return Optional.ofNullable(expiresAt);
    }

    @Override
    public String toString() {
        return "SqlLogin[username=" + username + ", expiresAt=" + expiresAt + "]";
    }
}
