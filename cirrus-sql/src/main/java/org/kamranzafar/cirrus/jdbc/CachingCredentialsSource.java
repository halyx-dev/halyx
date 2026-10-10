package org.kamranzafar.cirrus.jdbc;

import org.kamranzafar.cirrus.sql.spi.SqlCredentialsSource;
import org.kamranzafar.cirrus.sql.spi.SqlLogin;

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
