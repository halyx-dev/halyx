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
