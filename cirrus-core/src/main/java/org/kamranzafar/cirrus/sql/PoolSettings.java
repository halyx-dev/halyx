package org.kamranzafar.cirrus.sql;

import java.time.Duration;
import java.util.Objects;

/**
 * Connection pool sizing and timeouts.
 *
 * @param maxSize           most connections open at once (default 10)
 * @param minIdle           idle connections kept ready (default: same as {@code maxSize}, a fixed-size pool)
 * @param connectionTimeout longest wait for a connection from the pool (default 30s)
 * @param idleTimeout       idle connections above {@code minIdle} are closed after this (default 10m)
 * @param maxLifetime       connections are replaced after this, which also bounds how long a rotated
 *                          password or revoked identity keeps working (default 30m)
 */
public record PoolSettings(int maxSize, int minIdle, Duration connectionTimeout, Duration idleTimeout,
                           Duration maxLifetime) {

    public static final PoolSettings DEFAULTS = new PoolSettings(10, 10, Duration.ofSeconds(30),
            Duration.ofMinutes(10), Duration.ofMinutes(30));

    public PoolSettings {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be at least 1");
        }
        if (minIdle < 0 || minIdle > maxSize) {
            throw new IllegalArgumentException("minIdle must be between 0 and maxSize");
        }
        Objects.requireNonNull(connectionTimeout, "connectionTimeout");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Objects.requireNonNull(maxLifetime, "maxLifetime");
    }
}
