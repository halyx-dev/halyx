package org.kamranzafar.cirrus.cache;

import java.util.Objects;
import java.util.Optional;

/**
 * How to log in to the cache server. Separate from the provider's cloud credentials, which are used
 * to obtain {@link Iam} tokens.
 */
public sealed interface CacheAuth {

    static CacheAuth none() {
        return None.INSTANCE;
    }

    /** Password-only login ({@code AUTH <password>}), such as an ElastiCache AUTH token or Azure access key. */
    static CacheAuth password(String password) {
        return new Password(Optional.empty(), password);
    }

    /** ACL login ({@code AUTH <username> <password>}). */
    static CacheAuth password(String username, String password) {
        return new Password(Optional.of(username), password);
    }

    /**
     * Short-lived tokens from the provider's cloud identity: ElastiCache IAM (the username is the
     * ElastiCache user id), Entra ID for Azure (the username defaults to the token's object id) and
     * Memorystore IAM on GCP (no username).
     */
    static CacheAuth iam() {
        return new Iam(Optional.empty());
    }

    static CacheAuth iam(String username) {
        return new Iam(Optional.of(username));
    }

    record None() implements CacheAuth {
        static final None INSTANCE = new None();
    }

    record Password(Optional<String> username, String password) implements CacheAuth {
        public Password {
            Objects.requireNonNull(username, "username");
            Objects.requireNonNull(password, "password");
        }

        @Override
        public String toString() {
            return "Password[username=" + username.orElse("") + ", password=****]";
        }
    }

    record Iam(Optional<String> username) implements CacheAuth {
        public Iam {
            Objects.requireNonNull(username, "username");
        }
    }
}
