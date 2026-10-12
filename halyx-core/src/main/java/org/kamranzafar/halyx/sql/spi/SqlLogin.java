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
package org.kamranzafar.halyx.sql.spi;

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
