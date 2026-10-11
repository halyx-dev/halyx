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
package org.kamranzafar.cirrus.gcp;

import com.google.api.client.http.HttpResponseException;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.BaseServiceException;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.error.AlreadyExistsException;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.error.UnavailableException;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;

import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;

/** Translates Google Cloud exceptions to the portable error model. Shared by all GCP service drivers. */
public final class GcpErrors {

    private GcpErrors() {
    }

    public static Optional<CloudException> translate(Throwable error) {
        String provider = GcpProvider.ID;
        if (error instanceof BaseServiceException e) {
            String reason = e.getReason();
            String message = e.getMessage();
            CloudException mapped = switch (statusOf(e)) {
                case 404 -> new NotFoundException(message, e, provider, reason);
                case 412 -> new PreconditionFailedException(message, e, provider, reason);
                case 409 -> new AlreadyExistsException(message, e, provider, reason);
                case 401, 403 -> new AccessDeniedException(message, e, provider, reason);
                case 400, 416 -> new InvalidArgumentException(message, e, provider, reason);
                case 429 -> new ThrottledException(message, e, provider, reason, retryAfter(e));
                case 501 -> new UnsupportedFeatureException(message, e, provider, reason);
                // Code 0 means the request never got an HTTP response (network failure).
                case 0 -> new UnavailableException(message, e, provider, reason);
                default -> statusOf(e) >= 500 ? new UnavailableException(message, e, provider, reason) : null;
            };
            return Optional.ofNullable(mapped);
        }
        if (error instanceof ApiException e) {
            String reason = e.getStatusCode().getCode().name();
            String message = e.getMessage();
            CloudException mapped = switch (e.getStatusCode().getCode()) {
                case NOT_FOUND -> new NotFoundException(message, e, provider, reason);
                case ALREADY_EXISTS -> new AlreadyExistsException(message, e, provider, reason);
                case PERMISSION_DENIED, UNAUTHENTICATED -> new AccessDeniedException(message, e, provider, reason);
                case INVALID_ARGUMENT, FAILED_PRECONDITION, OUT_OF_RANGE -> new InvalidArgumentException(message, e, provider, reason);
                case RESOURCE_EXHAUSTED -> new ThrottledException(message, e, provider, reason);
                case UNAVAILABLE, DEADLINE_EXCEEDED, INTERNAL, ABORTED -> new UnavailableException(message, e, provider, reason);
                case UNIMPLEMENTED -> new UnsupportedFeatureException(message, e, provider, reason);
                default -> null;
            };
            return Optional.ofNullable(mapped);
        }
        if (error instanceof UncheckedIOException) {
            return Optional.of(new UnavailableException(error.getMessage(), error, provider, null));
        }
        return Optional.empty();
    }

    /**
     * Some clients (the XML multipart client) report HTTP errors with code 0, which otherwise means "no
     * response"; the real status is on the underlying HttpResponseException.
     */
    private static int statusOf(BaseServiceException e) {
        if (e.getCode() != 0) {
            return e.getCode();
        }
        for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
            if (t instanceof HttpResponseException http) {
                return http.getStatusCode();
            }
        }
        return 0;
    }

    /** The HTTP clients keep the response headers on the underlying HttpResponseException. */
    private static Duration retryAfter(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof HttpResponseException http) {
                return ThrottledException.parseRetryAfter(http.getHeaders().getFirstHeaderStringValue("Retry-After"));
            }
        }
        return null;
    }
}
