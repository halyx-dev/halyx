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
package org.kamranzafar.halyx.azure.redis;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.redis.RedisLogin;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntraIdRedisTokensTest {

    private static final OffsetDateTime EXPIRY = OffsetDateTime.of(2026, 10, 10, 13, 0, 0, 0, ZoneOffset.UTC);

    private static String jwt(String claims) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    private static final class FakeCredential implements TokenCredential {
        final List<List<String>> scopes = new ArrayList<>();
        final String token;

        FakeCredential(String token) {
            this.token = token;
        }

        @Override
        public Mono<AccessToken> getToken(TokenRequestContext request) {
            scopes.add(request.getScopes());
            return Mono.just(new AccessToken(token, EXPIRY));
        }
    }

    @Test
    void usesTheRedisScopeAndTheObjectIdAsUsername() {
        String token = jwt("{\"aud\":\"https://redis.azure.com\",\"oid\":\"5d0c1a3e-1111-2222-3333-444455556666\"}");
        FakeCredential credential = new FakeCredential(token);

        RedisLogin login = new EntraIdRedisTokens(credential, Optional.empty()).login();

        assertThat(credential.scopes).containsExactly(List.of("https://redis.azure.com/.default"));
        assertThat(login.username()).hasValue("5d0c1a3e-1111-2222-3333-444455556666");
        assertThat(login.password()).isEqualTo(token);
        assertThat(login.expiresAt()).hasValue(EXPIRY.toInstant());
    }

    @Test
    void explicitUsernameWins() {
        RedisLogin login = new EntraIdRedisTokens(new FakeCredential(jwt("{\"oid\":\"x\"}")), Optional.of("my-app")).login();

        assertThat(login.username()).hasValue("my-app");
    }

    @Test
    void tokenWithoutObjectIdNeedsAnExplicitUsername() {
        var tokens = new EntraIdRedisTokens(new FakeCredential(jwt("{\"sub\":\"x\"}")), Optional.empty());

        assertThatThrownBy(tokens::login).hasMessageContaining("oid");
    }
}
