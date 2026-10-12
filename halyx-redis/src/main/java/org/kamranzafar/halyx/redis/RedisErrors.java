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
package org.kamranzafar.halyx.redis;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import org.kamranzafar.halyx.error.AccessDeniedException;
import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.error.InvalidArgumentException;
import org.kamranzafar.halyx.error.UnavailableException;
import org.kamranzafar.halyx.error.UnknownCloudException;

import javax.net.ssl.SSLHandshakeException;
import java.security.cert.CertificateException;
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
                // An untrusted server certificate is a configuration problem, not a transient one.
                if (t instanceof SSLHandshakeException || t instanceof CertificateException) {
                    return Optional.of(new AccessDeniedException("TLS handshake failed (" + t.getMessage()
                            + "); if the server uses a private CA, set the '" + RedisCaches.CA_CERTIFICATE
                            + "' cache property", e, provider, "TLS"));
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
