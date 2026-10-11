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
package org.kamranzafar.cirrus.gcp.memorystore;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.kamranzafar.cirrus.redis.RedisCredentialsSource;
import org.kamranzafar.cirrus.redis.RedisLogin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * IAM authentication for Memorystore (Redis Cluster and Valkey): the identity's OAuth 2.0 access
 * token is the {@code AUTH} password. Tokens are refreshed and re-sent before they expire.
 */
public final class MemorystoreIamTokens implements RedisCredentialsSource {

    public static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    /** Used when a credential does not report an expiry: Google access tokens last an hour. */
    private static final Duration ASSUMED_LIFETIME = Duration.ofMinutes(55);

    private final GoogleCredentials credentials;
    private final Optional<String> username;

    public MemorystoreIamTokens(GoogleCredentials credentials, Optional<String> username) {
        this.credentials = Objects.requireNonNull(credentials, "credentials").createScoped(List.of(SCOPE));
        this.username = Objects.requireNonNull(username, "username");
    }

    /** Application Default Credentials: workload identity, a service account key, or gcloud login. */
    public static MemorystoreIamTokens applicationDefault(Optional<String> username) {
        try {
            return new MemorystoreIamTokens(GoogleCredentials.getApplicationDefault(), username);
        } catch (IOException e) {
            throw new UncheckedIOException("No Google Application Default Credentials for Memorystore IAM auth", e);
        }
    }

    @Override
    public RedisLogin login() {
        try {
            credentials.refreshIfExpired();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not obtain a Google access token for Memorystore", e);
        }
        AccessToken token = credentials.getAccessToken();
        Instant expiry = token.getExpirationTime() != null
                ? token.getExpirationTime().toInstant()
                : Instant.now().plus(ASSUMED_LIFETIME);
        return RedisLogin.token(username.orElse(null), token.getTokenValue(), expiry);
    }
}
