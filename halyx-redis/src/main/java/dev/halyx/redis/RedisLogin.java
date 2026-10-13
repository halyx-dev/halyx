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
package dev.halyx.redis;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Credentials for one {@code AUTH}: an optional ACL username, a password or token, and when a token
 * stops being accepted for new logins.
 */
public record RedisLogin(Optional<String> username, String password, Optional<Instant> expiresAt) {

    public RedisLogin {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public static RedisLogin of(String username, String password) {
        return new RedisLogin(Optional.ofNullable(username), password, Optional.empty());
    }

    public static RedisLogin token(String username, String token, Instant expiresAt) {
        return new RedisLogin(Optional.ofNullable(username), token, Optional.of(expiresAt));
    }

    @Override
    public String toString() {
        return "RedisLogin[username=" + username.orElse("") + ", password=****, expiresAt=" + expiresAt.orElse(null) + "]";
    }
}
