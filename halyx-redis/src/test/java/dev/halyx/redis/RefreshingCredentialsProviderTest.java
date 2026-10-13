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
package dev.halyx.redis;

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
