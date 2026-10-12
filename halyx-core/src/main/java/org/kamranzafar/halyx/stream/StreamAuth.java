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
package org.kamranzafar.halyx.stream;

import java.util.Objects;

/**
 * How to log in to the stream cluster. Separate from the provider's cloud credentials, which are used to
 * obtain {@link Iam} tokens.
 */
public sealed interface StreamAuth {

    /** No login: a plaintext listener, or TLS without SASL ({@code kafka.security.protocol=SSL}). */
    static StreamAuth none() {
        return None.INSTANCE;
    }

    /** A username and password over TLS, such as SCRAM credentials for MSK or an Event Hubs connection string. */
    static StreamAuth password(SaslMechanism mechanism, String username, String password) {
        return new Password(mechanism, username, password);
    }

    /**
     * Short-lived tokens from the provider's cloud identity over TLS (SASL {@code OAUTHBEARER}): MSK IAM access
     * control, Microsoft Entra ID for Event Hubs, and Google IAM for Managed Service for Apache Kafka. Clients
     * fetch a new token before the current one expires.
     */
    static StreamAuth iam() {
        return Iam.INSTANCE;
    }

    record None() implements StreamAuth {
        static final None INSTANCE = new None();
    }

    record Password(SaslMechanism mechanism, String username, String password) implements StreamAuth {
        public Password {
            Objects.requireNonNull(mechanism, "mechanism");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "Password[mechanism=" + mechanism + ", username=" + username + ", password=****]";
        }
    }

    record Iam() implements StreamAuth {
        static final Iam INSTANCE = new Iam();
    }
}
