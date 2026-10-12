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
