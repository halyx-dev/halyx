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
package org.kamranzafar.halyx.gcp.kafka;

import com.google.auth.ServiceAccountSigner;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.kamranzafar.halyx.kafka.KafkaCredentialsSource;
import org.kamranzafar.halyx.kafka.KafkaLogin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Google IAM login to Google Cloud Managed Service for Apache Kafka over SASL {@code OAUTHBEARER}. The token
 * wraps the identity's OAuth 2.0 access token the way Google's Managed Kafka login handlers do: a JWT-shaped
 * {@code header.claims.accessToken}, base64url-encoded, whose header names the {@code GOOG_OAUTH2_TOKEN}
 * algorithm and whose claims name the principal. The identity needs the Managed Kafka Client role.
 */
public final class ManagedKafkaTokens implements KafkaCredentialsSource {

    public static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    static final String HEADER = "{\"typ\":\"JWT\",\"alg\":\"GOOG_OAUTH2_TOKEN\"}";

    /** Used when a credential does not report an expiry: Google access tokens last an hour. */
    private static final Duration ASSUMED_LIFETIME = Duration.ofMinutes(55);

    private final GoogleCredentials credentials;
    private final String principal;
    private final Clock clock;

    public ManagedKafkaTokens(GoogleCredentials credentials, Optional<String> principal) {
        this(credentials, principal, Clock.systemUTC());
    }

    ManagedKafkaTokens(GoogleCredentials credentials, Optional<String> principal, Clock clock) {
        this.credentials = Objects.requireNonNull(credentials, "credentials").createScoped(List.of(SCOPE));
        this.principal = principal.orElseGet(() -> account(credentials));
        this.clock = clock;
    }

    /** Application Default Credentials: workload identity, a service account key, or gcloud login. */
    public static ManagedKafkaTokens applicationDefault(Optional<String> principal) {
        try {
            return new ManagedKafkaTokens(GoogleCredentials.getApplicationDefault(), principal);
        } catch (IOException e) {
            throw new UncheckedIOException("No Google Application Default Credentials for Managed Kafka", e);
        }
    }

    @Override
    public KafkaLogin login() {
        try {
            credentials.refreshIfExpired();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not obtain a Google access token for Managed Kafka", e);
        }
        AccessToken accessToken = credentials.getAccessToken();
        Instant now = clock.instant();
        Instant expiry = accessToken.getExpirationTime() != null
                ? accessToken.getExpirationTime().toInstant()
                : now.plus(ASSUMED_LIFETIME);
        String claims = "{\"exp\":" + expiry.getEpochSecond() + ",\"iat\":" + now.getEpochSecond()
                + ",\"iss\":\"Google\",\"scope\":\"kafka\",\"sub\":\"" + principal + "\"}";
        String token = base64(HEADER) + "." + base64(claims) + "." + base64(accessToken.getTokenValue());
        return new KafkaLogin(token, principal, now, expiry);
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** The service account the credentials act as. */
    static String account(GoogleCredentials credentials) {
        if (!(credentials instanceof ServiceAccountSigner account) || account.getAccount() == null) {
            throw new IllegalArgumentException("Cannot tell which principal these Google credentials are for "
                    + "(a gcloud user login?): set the stream property 'gcp.kafka.principal' to its email");
        }
        return account.getAccount();
    }
}
