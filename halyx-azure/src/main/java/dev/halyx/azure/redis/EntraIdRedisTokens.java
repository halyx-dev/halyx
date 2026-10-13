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
package dev.halyx.azure.redis;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import dev.halyx.redis.RedisCredentialsSource;
import dev.halyx.redis.RedisLogin;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Microsoft Entra ID tokens for Azure Cache for Redis and Azure Managed Redis. The token is the
 * password; the username is the identity's object id, taken from the token's {@code oid} claim
 * unless given explicitly. Tokens are refreshed and re-sent before they expire.
 */
public final class EntraIdRedisTokens implements RedisCredentialsSource {

    public static final String SCOPE = "https://redis.azure.com/.default";

    private static final Pattern OBJECT_ID = Pattern.compile("\"oid\"\\s*:\\s*\"([^\"]+)\"");

    private final TokenCredential credential;
    private final Optional<String> username;

    public EntraIdRedisTokens(TokenCredential credential, Optional<String> username) {
        this.credential = Objects.requireNonNull(credential, "credential");
        this.username = Objects.requireNonNull(username, "username");
    }

    @Override
    public RedisLogin login() {
        AccessToken token = credential.getTokenSync(new TokenRequestContext().addScopes(SCOPE));
        String user = username.orElseGet(() -> objectId(token.getToken()));
        return RedisLogin.token(user, token.getToken(), token.getExpiresAt().toInstant());
    }

    /** The {@code oid} claim of a JWT access token. */
    static String objectId(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Entra ID access token is not a JWT; set the username explicitly");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        Matcher matcher = OBJECT_ID.matcher(payload);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Entra ID access token has no oid claim; set the username explicitly");
        }
        return matcher.group(1);
    }
}
