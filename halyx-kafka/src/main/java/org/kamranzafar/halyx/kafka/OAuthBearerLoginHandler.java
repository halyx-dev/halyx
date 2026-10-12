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
package org.kamranzafar.halyx.kafka;

import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.security.auth.SaslExtensionsCallback;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerToken;
import org.apache.kafka.common.security.oauthbearer.OAuthBearerTokenCallback;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client-side {@code OAUTHBEARER} login handler for {@code StreamAuth.iam()}. Kafka creates it from
 * {@code sasl.login.callback.handler.class} and calls it to log in and again before the token expires; it
 * hands over a login from the {@link KafkaCredentialsSource} that {@link KafkaStreamClients} put in the client's
 * configuration.
 */
public final class OAuthBearerLoginHandler implements AuthenticateCallbackHandler {

    static final String MECHANISM = "OAUTHBEARER";

    private KafkaCredentialsSource credentials;

    @Override
    public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {
        if (!MECHANISM.equals(saslMechanism)) {
            throw new IllegalArgumentException("Halyx IAM login uses SASL " + MECHANISM + ", not " + saslMechanism);
        }
        if (!(configs.get(KafkaStreamClients.CREDENTIALS) instanceof KafkaCredentialsSource source)) {
            throw new IllegalArgumentException("No " + KafkaStreamClients.CREDENTIALS + " in the client configuration; "
                    + "this handler only works for clients Halyx creates");
        }
        credentials = source;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {
            if (callback instanceof OAuthBearerTokenCallback tokenCallback) {
                tokenCallback.token(token(login()));
            } else if (!(callback instanceof SaslExtensionsCallback)) {
                // SASL extensions stay empty; anything else is unexpected.
                throw new UnsupportedCallbackException(callback);
            }
        }
    }

    private KafkaLogin login() throws IOException {
        if (credentials == null) {
            throw new IllegalStateException("Not configured");
        }
        try {
            return credentials.login();
        } catch (RuntimeException e) {
            throw new IOException("Could not obtain a Kafka login token: " + e.getMessage(), e);
        }
    }

    static OAuthBearerToken token(KafkaLogin login) {
        long issued = login.issuedAt().toEpochMilli();
        long expires = login.expiresAt().toEpochMilli();
        return new OAuthBearerToken() {
            @Override
            public String value() {
                return login.token();
            }

            @Override
            public Set<String> scope() {
                return Set.of();
            }

            @Override
            public long lifetimeMs() {
                return expires;
            }

            @Override
            public String principalName() {
                return login.principal();
            }

            @Override
            public Long startTimeMs() {
                return issued;
            }
        };
    }

    @Override
    public void close() {
        credentials = null;
    }
}
