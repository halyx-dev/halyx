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
package org.kamranzafar.halyx.error;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * The provider is rate limiting the caller. Safe to retry with backoff, after at least
 * {@link #retryAfter()} when the provider said how long to wait.
 */
public final class ThrottledException extends CloudException {

    private final Duration retryAfter;

    public ThrottledException(String message) {
        this(message, null, null, null);
    }

    public ThrottledException(String message, Throwable cause, String provider, String providerErrorCode) {
        this(message, cause, provider, providerErrorCode, null);
    }

    public ThrottledException(String message, Throwable cause, String provider, String providerErrorCode,
                              Duration retryAfter) {
        super(message, cause, provider, providerErrorCode);
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? null : retryAfter;
    }

    /** How long the provider asked callers to wait (a {@code Retry-After} header), if it said. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }

    /**
     * Parses a {@code Retry-After} header: delay seconds ({@code 120}) or an HTTP date. Returns null for
     * a missing or malformed value.
     */
    public static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        try {
            return Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration wait = Duration.between(Instant.now(), at);
                return wait.isNegative() ? Duration.ZERO : wait;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }
}
