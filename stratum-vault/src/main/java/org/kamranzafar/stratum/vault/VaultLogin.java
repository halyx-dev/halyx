package org.kamranzafar.stratum.vault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Obtains a Vault token (Strategy): a fixed token, or a login with an auth method. */
interface VaultLogin {

    record Token(String token, Instant issuedAt, Optional<Instant> expiresAt) {

        /** Renew in the last fifth of the token's life, but at least 30 s before expiry when possible. */
        boolean expiringSoon(Instant now) {
            return expiresAt.map(expiry -> {
                Duration lifetime = Duration.between(issuedAt, expiry);
                Duration margin = lifetime.dividedBy(5).compareTo(Duration.ofSeconds(30)) < 0
                        ? lifetime.dividedBy(5) : Duration.ofSeconds(30);
                return now.isAfter(expiry.minus(margin));
            }).orElse(false);
        }
    }

    Token login(VaultClient client);

    /** Whether logging in again can produce a fresh token (false for a fixed token). */
    default boolean renewable() {
        return true;
    }

    static VaultLogin token(String token) {
        return new VaultLogin() {
            @Override
            public Token login(VaultClient client) {
                return new Token(token, Instant.now(), Optional.empty());
            }

            @Override
            public boolean renewable() {
                return false;
            }
        };
    }

    static VaultLogin appRole(String path, String roleId, String secretId) {
        return client -> fromAuth(client.unauthenticated("POST", "auth/" + path + "/login",
                Map.of("role_id", roleId, "secret_id", secretId)));
    }

    static VaultLogin kubernetes(String path, String role, Path jwtFile) {
        return client -> {
            try {
                String jwt = Files.readString(jwtFile).trim();
                return fromAuth(client.unauthenticated("POST", "auth/" + path + "/login", Map.of("role", role, "jwt", jwt)));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the Kubernetes service account token " + jwtFile, e);
            }
        };
    }

    private static Token fromAuth(Object response) {
        Object token = Json.at(response, "auth", "client_token");
        if (!(token instanceof String clientToken)) {
            throw new IllegalStateException("Vault login returned no client token");
        }
        long lease = Json.at(response, "auth", "lease_duration") instanceof Number n ? n.longValue() : 0;
        Instant now = Instant.now();
        return new Token(clientToken, now,
                lease > 0 ? Optional.of(now.plusSeconds(lease)) : Optional.empty());
    }
}
