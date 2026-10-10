package org.kamranzafar.cirrus.redis;

/**
 * Produces logins, typically by minting a cloud IAM token (a Strategy each provider implements).
 * Called on every new connection and before the previous token expires; it may block.
 */
@FunctionalInterface
public interface RedisCredentialsSource {

    RedisLogin login();
}
