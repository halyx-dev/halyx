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
package dev.halyx.sql.spi;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Reuses an expiring login (a cloud IAM token) until shortly before it expires, so a burst of new
 * connections costs one token request rather than one each. Logins without an expiry are never cached:
 * a password supplier is asked every time, which is what lets rotated secrets take effect.
 */
public final class CachingCredentialsSource implements SqlCredentialsSource {

    /** Refresh this long before expiry, or after 80% of the token's lifetime, whichever is earlier. */
    static final Duration MARGIN = Duration.ofMinutes(2);

    private final SqlCredentialsSource delegate;
    private final Clock clock;
    private SqlLogin cached;
    private Instant refreshAt;

    public CachingCredentialsSource(SqlCredentialsSource delegate) {
        this(delegate, Clock.systemUTC());
    }

    CachingCredentialsSource(SqlCredentialsSource delegate, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.clock = clock;
    }

    @Override
    public synchronized SqlLogin login() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(refreshAt)) {
            return cached;
        }
        SqlLogin login = delegate.login();
        if (login.expiresAt() == null) {
            cached = null;
            return login;
        }
        Duration lifetime = Duration.between(now, login.expiresAt());
        Duration early = lifetime.dividedBy(5).compareTo(MARGIN) < 0 ? lifetime.dividedBy(5) : MARGIN;
        cached = login;
        refreshAt = login.expiresAt().minus(early);
        return login;
    }
}
