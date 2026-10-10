package org.kamranzafar.cirrus.redis;

import io.lettuce.core.RedisCredentials;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshingCredentialsProviderTest {

    @Test
    void passwordLoginsAreResolvedOnce() {
        AtomicInteger calls = new AtomicInteger();
        try (var provider = new RefreshingCredentialsProvider(() -> {
            calls.incrementAndGet();
            return RedisLogin.of(null, "pw");
        })) {
            RedisCredentials first = provider.resolveCredentials().block();
            provider.resolveCredentials().block();

            assertThat(first.hasUsername()).isFalse();
            assertThat(new String(first.getPassword())).isEqualTo("pw");
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void validTokensAreReusedUntilTheyNearExpiry() {
        AtomicInteger calls = new AtomicInteger();
        try (var provider = new RefreshingCredentialsProvider(() -> RedisLogin.token("u",
                "t" + calls.incrementAndGet(), Instant.now().plus(Duration.ofHours(1))))) {
            provider.resolveCredentials().block();
            provider.resolveCredentials().block();

            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void tokensNearExpiryAreRefreshedAndPublished() {
        AtomicInteger calls = new AtomicInteger();
        Instant soon = Instant.now().plus(RefreshingCredentialsProvider.REFRESH_MARGIN).plusMillis(300);
        try (var provider = new RefreshingCredentialsProvider(() -> calls.incrementAndGet() == 1
                ? RedisLogin.token("u", "first", soon)
                : RedisLogin.token("u", "second", Instant.now().plus(Duration.ofHours(1))))) {
            assertThat(new String(provider.resolveCredentials().block().getPassword())).isEqualTo("first");

            RedisCredentials published = provider.credentials().blockFirst(Duration.ofSeconds(5));

            assertThat(new String(published.getPassword())).isEqualTo("second");
            assertThat(new String(provider.resolveCredentials().block().getPassword())).isEqualTo("second");
        }
    }
}
