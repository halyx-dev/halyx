package org.kamranzafar.cirrus.azure.redis;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import org.kamranzafar.cirrus.redis.RedisCredentialsSource;
import org.kamranzafar.cirrus.redis.RedisLogin;

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
