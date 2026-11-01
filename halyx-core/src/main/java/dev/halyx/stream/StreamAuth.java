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
package dev.halyx.stream;

import java.util.Objects;
import java.util.function.Supplier;

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
     * A password asked for each time a connection logs in, so a rotated secret takes effect without a restart:
     * new connections, and open ones when the broker has them log in again ({@code connections.max.reauth.ms}).
     * For example, from a cached parameter store:
     * <pre>{@code StreamAuth.password(SaslMechanism.SCRAM_SHA_512, "app", () -> secrets.require("msk/app", "password"))}</pre>
     */
    static StreamAuth password(SaslMechanism mechanism, String username, Supplier<String> password) {
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

    /** @param passwordSupplier asked for the password; equal for equal fixed passwords */
    record Password(SaslMechanism mechanism, String username, Supplier<String> passwordSupplier) implements StreamAuth {
        public Password {
            Objects.requireNonNull(mechanism, "mechanism");
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(passwordSupplier, "passwordSupplier");
        }

        public Password(SaslMechanism mechanism, String username, String password) {
            this(mechanism, username, new FixedPassword(Objects.requireNonNull(password, "password")));
        }

        /** The password; a supplied one is asked now. */
        public String password() {
            return passwordSupplier.get();
        }

        /** Whether the password was given as a supplier, so it may change while clients are open. */
        public boolean supplied() {
            return !(passwordSupplier instanceof FixedPassword);
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
