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
package org.kamranzafar.halyx.sql;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** How connections to a database log in. */
public sealed interface SqlAuth {

    /** A fixed username and password. */
    static SqlAuth password(String username, String password) {
        Objects.requireNonNull(password, "password");
        return new Password(username, () -> password);
    }

    /**
     * A password looked up for every new physical connection, so a rotated secret takes effect without a
     * restart. For example, from a cached parameter store:
     * <pre>{@code SqlAuth.password("app", () -> secrets.require("prod/db", "password"))}</pre>
     */
    static SqlAuth password(String username, Supplier<String> password) {
        return new Password(username, password);
    }

    /**
     * Short-lived tokens from the provider's cloud identity instead of a password: RDS IAM authentication
     * (PostgreSQL, MySQL), Microsoft Entra ID (Azure Database for PostgreSQL and MySQL, Azure SQL) and
     * Cloud SQL IAM database authentication (PostgreSQL, MySQL). Tokens are fetched as needed and cached
     * until shortly before they expire; established connections stay logged in. TLS is required.
     *
     * @param username the database user mapped to the identity. Required on AWS and for Azure PostgreSQL
     *                 and MySQL; not used for Azure SQL; derived from the service account on GCP if omitted
     */
    static SqlAuth iam(String username) {
        return new Iam(Optional.of(username));
    }

    /** Cloud identity login with the username derived where the provider can (GCP service accounts). */
    static SqlAuth iam() {
        return new Iam(Optional.empty());
    }

    record Password(String username, Supplier<String> password) implements SqlAuth {
        public Password {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "Password[username=" + username + ", password=****]";
        }
    }

    record Iam(Optional<String> username) implements SqlAuth {
        public Iam {
            Objects.requireNonNull(username, "username");
        }
    }
}
