package org.kamranzafar.stratum.redis;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import org.kamranzafar.stratum.error.AccessDeniedException;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.error.InvalidArgumentException;
import org.kamranzafar.stratum.error.UnavailableException;
import org.kamranzafar.stratum.error.UnknownCloudException;

import java.util.Optional;
import java.util.Set;

/** Translates Lettuce exceptions and Redis error replies to the portable error model. */
public final class RedisErrors {

    private static final Set<String> AUTH = Set.of("NOAUTH", "WRONGPASS", "NOPERM");
    private static final Set<String> TRANSIENT = Set.of("LOADING", "BUSY", "TRYAGAIN", "CLUSTERDOWN", "MASTERDOWN",
            "READONLY", "OOM");

    private RedisErrors() {
    }

    public static Optional<CloudException> translate(Throwable error, String provider) {
        if (error instanceof RedisCommandExecutionException e) {
            String code = code(e.getMessage());
            String message = e.getMessage();
            if (AUTH.contains(code)) {
                return Optional.of(new AccessDeniedException(message, e, provider, code));
            }
            if (TRANSIENT.contains(code)) {
                return Optional.of(new UnavailableException(message, e, provider, code));
            }
            if ("WRONGTYPE".equals(code) || message.contains("not an integer") || message.contains("overflow")) {
                return Optional.of(new InvalidArgumentException(message, e, provider, code));
            }
            return Optional.of(new UnknownCloudException(message, e, provider, code));
        }
        if (error instanceof RedisConnectionException e) {
            // A failed handshake AUTH surfaces as a connection error with the reply as a cause.
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof RedisCommandExecutionException auth && AUTH.contains(code(auth.getMessage()))) {
                    return Optional.of(new AccessDeniedException(auth.getMessage(), e, provider, code(auth.getMessage())));
                }
            }
            return Optional.of(new UnavailableException(e.getMessage(), e, provider, null));
        }
        if (error instanceof RedisCommandTimeoutException || error instanceof RedisException) {
            return Optional.of(new UnavailableException(error.getMessage(), error, provider, null));
        }
        return Optional.empty();
    }

    private static String code(String message) {
        if (message == null) {
            return "";
        }
        int space = message.indexOf(' ');
        return space < 0 ? message : message.substring(0, space);
    }
}
