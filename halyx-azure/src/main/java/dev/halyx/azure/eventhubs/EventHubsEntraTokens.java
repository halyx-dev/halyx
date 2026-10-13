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
package dev.halyx.azure.eventhubs;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import dev.halyx.kafka.KafkaCredentialsSource;
import dev.halyx.kafka.KafkaLogin;

import java.time.Instant;
import java.util.Objects;

/**
 * Microsoft Entra ID access tokens for the Kafka endpoint of an Event Hubs namespace, over SASL
 * {@code OAUTHBEARER}. The scope is the namespace, {@code https://<namespace>.servicebus.windows.net/.default}; the
 * identity needs the Azure Event Hubs Data Sender or Receiver role on the namespace or event hub.
 */
public final class EventHubsEntraTokens implements KafkaCredentialsSource {

    static final String PRINCIPAL = "entra-id";

    private final TokenCredential credential;
    private final String scope;

    public EventHubsEntraTokens(TokenCredential credential, String scope) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /** The scope for the namespace a bootstrap server such as {@code orders.servicebus.windows.net:9093} names. */
    public static String scope(String bootstrapServer) {
        int colon = bootstrapServer.lastIndexOf(':');
        String host = colon < 0 ? bootstrapServer : bootstrapServer.substring(0, colon);
        return "https://" + host + "/.default";
    }

    @Override
    public KafkaLogin login() {
        AccessToken token = credential.getTokenSync(new TokenRequestContext().addScopes(scope));
        return new KafkaLogin(token.getToken(), PRINCIPAL, Instant.now(), token.getExpiresAt().toInstant());
    }
}
