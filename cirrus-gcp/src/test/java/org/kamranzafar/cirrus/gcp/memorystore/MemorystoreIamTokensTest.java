package org.kamranzafar.cirrus.gcp.memorystore;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.redis.RedisLogin;

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
