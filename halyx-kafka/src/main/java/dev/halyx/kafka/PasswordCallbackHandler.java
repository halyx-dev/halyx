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
package dev.halyx.kafka;

import dev.halyx.stream.StreamAuth;
import org.apache.kafka.common.security.auth.AuthenticateCallbackHandler;
import org.apache.kafka.common.security.auth.SaslExtensionsCallback;
import org.apache.kafka.common.security.scram.ScramExtensionsCallback;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.AppConfigurationEntry;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * The client-side {@code PLAIN} and {@code SCRAM} callback handler for a password given as a supplier. Kafka creates it
 * from {@code sasl.client.callback.handler.class} and calls it each time a connection logs in, so a rotated password
 * reaches new connections, and open ones when the broker has them log in again. It asks the
 * {@link StreamAuth.Password} that {@link KafkaStreamClients} put in the client's configuration.
 */
public final class PasswordCallbackHandler implements AuthenticateCallbackHandler {

    private StreamAuth.Password login;

    @Override
    public void configure(Map<String, ?> configs, String saslMechanism, List<AppConfigurationEntry> jaasConfigEntries) {
        if (!(configs.get(KafkaStreamClients.PASSWORD) instanceof StreamAuth.Password password)) {
            throw new IllegalArgumentException("No " + KafkaStreamClients.PASSWORD + " in the client configuration; "
                    + "this handler only works for clients Halyx creates");
        }
        login = password;
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        if (login == null) {
            throw new IllegalStateException("Not configured");
        }
        for (Callback callback : callbacks) {
            switch (callback) {
                case NameCallback name -> name.setName(login.username());
                case PasswordCallback password -> password.setPassword(password().toCharArray());
                // SASL and SCRAM extensions stay empty; anything else is unexpected.
                case SaslExtensionsCallback ignored -> { }
                case ScramExtensionsCallback ignored -> { }
                default -> throw new UnsupportedCallbackException(callback);
            }
        }
    }

    private String password() throws IOException {
        try {
            return login.password();
        } catch (RuntimeException e) {
            throw new IOException("Could not obtain the Kafka password: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
    }
}
