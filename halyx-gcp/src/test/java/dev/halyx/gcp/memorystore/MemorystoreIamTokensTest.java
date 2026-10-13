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
package dev.halyx.gcp.memorystore;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.Test;
import dev.halyx.redis.RedisLogin;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MemorystoreIamTokensTest {

    @Test
    void accessTokenIsThePasswordWithItsExpiry() {
        Instant expiry = Instant.now().plus(Duration.ofMinutes(50));
        var credentials = GoogleCredentials.create(new AccessToken("ya29.test-token", Date.from(expiry)));

        RedisLogin login = new MemorystoreIamTokens(credentials, Optional.empty()).login();

        assertThat(login.password()).isEqualTo("ya29.test-token");
        assertThat(login.username()).isEmpty();
        assertThat(login.expiresAt()).hasValueSatisfying(e -> assertThat(e.toEpochMilli()).isEqualTo(expiry.toEpochMilli()));
    }

    @Test
    void optionalUsernameIsPassedThrough() {
        var credentials = GoogleCredentials.create(new AccessToken("t", Date.from(Instant.now().plusSeconds(3600))));

        assertThat(new MemorystoreIamTokens(credentials, Optional.of("default")).login().username()).hasValue("default");
    }
}
