package org.kamranzafar.cirrus.jdbc;

import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.sql.spi.SqlLogin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CachingCredentialsSourceTest {

    private Instant now = Instant.parse("2026-10-10T12:00:00Z");

    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };

    @Test
    void tokensAreReusedUntilShortlyBeforeExpiry() {
        var issued = new AtomicInteger();
        var source = new CachingCredentialsSource(
                () -> SqlLogin.token("app", "token-" + issued.incrementAndGet(), now.plus(Duration.ofMinutes(15))), clock);

        assertThat(source.login().password()).isEqualTo("token-1");
        now = now.plus(Duration.ofMinutes(12));
        assertThat(source.login().password()).isEqualTo("token-1");
        now = now.plus(Duration.ofMinutes(1).plusSeconds(1)); // within 2 minutes of expiry
        assertThat(source.login().password()).isEqualTo("token-2");
        assertThat(issued).hasValue(2);
    }

    @Test
    void shortLivedTokensRefreshAfterFourFifthsOfTheirLifetime() {
        var issued = new AtomicInteger();
        var source = new CachingCredentialsSource(
                () -> SqlLogin.token("app", "t" + issued.incrementAndGet(), now.plus(Duration.ofMinutes(5))), clock);

        source.login();
        now = now.plus(Duration.ofMinutes(4).plusSeconds(1));
        source.login();

        assertThat(issued).hasValue(2);
    }

    @Test
    void passwordsAreAskedForEveryTime() {
        var asked = new AtomicInteger();
        var source = new CachingCredentialsSource(() -> SqlLogin.password("app", "p" + asked.incrementAndGet()), clock);

        source.login();
        source.login();

        assertThat(asked).hasValue(2);
    }
}
