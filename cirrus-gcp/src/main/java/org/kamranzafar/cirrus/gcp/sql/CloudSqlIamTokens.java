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
package org.kamranzafar.cirrus.gcp.sql;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.kamranzafar.cirrus.sql.SqlEngine;
import org.kamranzafar.cirrus.sql.spi.SqlCredentialsSource;
import org.kamranzafar.cirrus.sql.spi.SqlLogin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Cloud SQL IAM database authentication over a direct TLS connection: the identity's OAuth 2.0 access
 * token, scoped to {@code sqlservice.login}, is the password. The database user is the IAM principal:
 * for a service account {@code app@project.iam.gserviceaccount.com} it is {@code app@project.iam} on
 * PostgreSQL and {@code app} on MySQL, which is what this derives when no username is given.
 */
public final class CloudSqlIamTokens implements SqlCredentialsSource {

    public static final String SCOPE = "https://www.googleapis.com/auth/sqlservice.login";

    /** Used when a credential does not report an expiry: Google access tokens last an hour. */
    private static final Duration ASSUMED_LIFETIME = Duration.ofMinutes(55);

    private final GoogleCredentials credentials;
    private final String username;

    public CloudSqlIamTokens(GoogleCredentials credentials, SqlEngine engine, Optional<String> username) {
        this.credentials = Objects.requireNonNull(credentials, "credentials").createScoped(List.of(SCOPE));
        this.username = username.orElseGet(() -> derivedUsername(credentials, engine));
    }

    /** Application Default Credentials: workload identity, a service account key, or gcloud login. */
    public static CloudSqlIamTokens applicationDefault(SqlEngine engine, Optional<String> username) {
        try {
            return new CloudSqlIamTokens(GoogleCredentials.getApplicationDefault(), engine, username);
        } catch (IOException e) {
            throw new UncheckedIOException("No Google Application Default Credentials for Cloud SQL IAM auth", e);
        }
    }

    /** The database user tokens are issued for. */
    public String username() {
        return username;
    }

    @Override
    public SqlLogin login() {
        try {
            credentials.refreshIfExpired();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not obtain a Google access token for Cloud SQL", e);
        }
        AccessToken token = credentials.getAccessToken();
        Instant expiry = token.getExpirationTime() != null
                ? token.getExpirationTime().toInstant()
                : Instant.now().plus(ASSUMED_LIFETIME);
        return SqlLogin.token(username, token.getTokenValue(), expiry);
    }

    static String derivedUsername(GoogleCredentials credentials, SqlEngine engine) {
        if (!(credentials instanceof ServiceAccountSigner account) || account.getAccount() == null) {
            throw new IllegalArgumentException("Cannot derive the Cloud SQL user from these credentials "
                    + "(a gcloud user login?): pass it with SqlAuth.iam(\"<user>\")");
        }
        String email = account.getAccount();
        return switch (engine) {
            case POSTGRESQL -> email.endsWith(".gserviceaccount.com")
                    ? email.substring(0, email.length() - ".gserviceaccount.com".length())
                    : email;
            case MYSQL -> email.substring(0, email.indexOf('@') < 0 ? email.length() : email.indexOf('@'));
            case SQLSERVER -> throw new IllegalArgumentException("Cloud SQL IAM authentication supports PostgreSQL and MySQL");
        };
    }
}
