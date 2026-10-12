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

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.kafka.KafkaLogin;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedKafkaTokensTest {

    /** The token must still be valid by the system clock, or the credentials try to refresh it. */
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Instant EXPIRY = NOW.plusSeconds(3600);

    private static GoogleCredentials fixedToken() {
        return GoogleCredentials.create(new AccessToken("ya29.access-token", Date.from(EXPIRY)));
    }

    private static String decode(String part) {
        return new String(Base64.getUrlDecoder().decode(part), StandardCharsets.UTF_8);
    }

    @Test
    void tokenWrapsTheAccessTokenWithHeaderAndClaims() {
        KafkaLogin login = new ManagedKafkaTokens(fixedToken(), Optional.of("app@project.iam.gserviceaccount.com"),
                Clock.fixed(NOW, ZoneOffset.UTC)).login();

        String[] parts = login.token().split("\\.");
        assertThat(parts).hasSize(3);
        assertThat(decode(parts[0])).isEqualTo("{\"typ\":\"JWT\",\"alg\":\"GOOG_OAUTH2_TOKEN\"}");
        assertThat(decode(parts[1])).isEqualTo("{\"exp\":" + EXPIRY.getEpochSecond() + ",\"iat\":" + NOW.getEpochSecond()
                + ",\"iss\":\"Google\",\"scope\":\"kafka\",\"sub\":\"app@project.iam.gserviceaccount.com\"}");
        assertThat(decode(parts[2])).isEqualTo("ya29.access-token");
        assertThat(login.principal()).isEqualTo("app@project.iam.gserviceaccount.com");
        assertThat(login.expiresAt()).isEqualTo(EXPIRY);
    }

    @Test
    void credentialsWithoutAnAccountNeedThePrincipalProperty() {
        assertThatThrownBy(() -> new ManagedKafkaTokens(fixedToken(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gcp.kafka.principal");
    }
}
