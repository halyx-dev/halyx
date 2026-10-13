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
package dev.halyx.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Portable HTTP client tuning for a provider connection, read from its {@link ProviderConfig} properties.
 * One pooled client is shared by every service of the connection (blob storage, parameter stores, ...).
 * Durations accept ISO-8601 ({@code PT30S}) or a number with a unit ({@code 500ms}, {@code 30s}, {@code 5m}).
 *
 * <ul>
 *   <li>{@value #MAX_CONNECTIONS}: pooled connections; bounds concurrent requests (default 200)</li>
 *   <li>{@value #CONNECT_TIMEOUT}: TCP/TLS connect timeout (default 10s)</li>
 *   <li>{@value #READ_TIMEOUT}: maximum wait for response data (default 60s)</li>
 *   <li>{@value #IDLE_TIMEOUT}: idle pooled connections are closed after this (default 60s)</li>
 *   <li>{@value #ACQUIRE_TIMEOUT}: maximum wait for a free pooled connection (default 10s)</li>
 * </ul>
 */
public record HttpSettings(int maxConnections, Duration connectTimeout, Duration readTimeout, Duration idleTimeout,
                           Duration acquireTimeout) {

    public static final String MAX_CONNECTIONS = "http.max-connections";
    public static final String CONNECT_TIMEOUT = "http.connect-timeout";
    public static final String READ_TIMEOUT = "http.read-timeout";
    public static final String IDLE_TIMEOUT = "http.idle-timeout";
    public static final String ACQUIRE_TIMEOUT = "http.acquire-timeout";

    public static final HttpSettings DEFAULTS = new HttpSettings(200, Duration.ofSeconds(10), Duration.ofSeconds(60),
            Duration.ofSeconds(60), Duration.ofSeconds(10));

    public HttpSettings {
        if (maxConnections < 1) {
            throw new IllegalArgumentException(MAX_CONNECTIONS + " must be at least 1");
        }
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Objects.requireNonNull(acquireTimeout, "acquireTimeout");
    }

    public static HttpSettings from(ProviderConfig config) {
        return new HttpSettings(
                config.property(MAX_CONNECTIONS).map(v -> Integer.parseInt(v.trim())).orElse(DEFAULTS.maxConnections),
                config.property(CONNECT_TIMEOUT).map(HttpSettings::duration).orElse(DEFAULTS.connectTimeout),
                config.property(READ_TIMEOUT).map(HttpSettings::duration).orElse(DEFAULTS.readTimeout),
                config.property(IDLE_TIMEOUT).map(HttpSettings::duration).orElse(DEFAULTS.idleTimeout),
                config.property(ACQUIRE_TIMEOUT).map(HttpSettings::duration).orElse(DEFAULTS.acquireTimeout));
    }

    static Duration duration(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.startsWith("p")) {
                return Duration.parse(v.toUpperCase(Locale.ROOT));
            }
            if (v.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(v.substring(0, v.length() - 2).trim()));
            }
            long amount = Long.parseLong(v.substring(0, v.length() - 1).trim());
            return switch (v.charAt(v.length() - 1)) {
                case 's' -> Duration.ofSeconds(amount);
                case 'm' -> Duration.ofMinutes(amount);
                case 'h' -> Duration.ofHours(amount);
                default -> throw new IllegalArgumentException();
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid duration '" + value + "': use e.g. 500ms, 30s, 5m or PT30S");
        }
    }
}
