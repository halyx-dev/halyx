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
package org.kamranzafar.halyx.azure.eventhubs;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.kafka.KafkaLogin;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventHubsEntraTokensTest {

    @Test
    void scopeIsTheNamespaceOfTheBootstrapServer() {
        assertThat(EventHubsEntraTokens.scope("orders.servicebus.windows.net:9093"))
                .isEqualTo("https://orders.servicebus.windows.net/.default");
        assertThat(EventHubsEntraTokens.scope("orders.servicebus.windows.net"))
                .isEqualTo("https://orders.servicebus.windows.net/.default");
    }

    @Test
    void loginIsTheEntraTokenForTheScopeWithItsExpiry() {
        OffsetDateTime expiry = OffsetDateTime.of(2026, 10, 10, 13, 0, 0, 0, ZoneOffset.UTC);
        List<String> scopes = new ArrayList<>();
        TokenCredential credential = new TokenCredential() {
            @Override
            public Mono<AccessToken> getToken(TokenRequestContext request) {
                return Mono.just(getTokenSync(request));
            }

            @Override
            public AccessToken getTokenSync(TokenRequestContext request) {
                scopes.addAll(request.getScopes());
                return new AccessToken("entra-token", expiry);
            }
        };

        KafkaLogin login = new EventHubsEntraTokens(credential, "https://orders.servicebus.windows.net/.default").login();

        assertThat(scopes).containsExactly("https://orders.servicebus.windows.net/.default");
        assertThat(login.token()).isEqualTo("entra-token");
        assertThat(login.expiresAt()).isEqualTo(expiry.toInstant());
    }
}
