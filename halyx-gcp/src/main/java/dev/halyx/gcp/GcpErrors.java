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
package dev.halyx.gcp;

import com.google.api.client.http.HttpResponseException;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.BaseServiceException;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.AlreadyExistsException;
import dev.halyx.error.CloudException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.PreconditionFailedException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnavailableException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.error.UnsupportedFeatureException;

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
            return Optional.ofNullable(mapStatus(statusOf(e), e.getMessage(), e, e.getReason(), retryAfter(e)));
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
     * Maps an HTTP status from a Google Cloud service, for drivers that make HTTP calls themselves, as
     * {@link #translate} maps the clients' exceptions; a status with no portable meaning is an
     * {@link UnknownCloudException}.
     */
    public static CloudException fromStatus(int status, String message, Throwable cause, String code, Duration retryAfter) {
        CloudException mapped = mapStatus(status, message, cause, code, retryAfter);
        return mapped != null ? mapped : new UnknownCloudException(message, cause, GcpProvider.ID, code);
    }

    /** The portable error for {@code status}, or null if it has none. */
    private static CloudException mapStatus(int status, String message, Throwable cause, String code, Duration retryAfter) {
        String provider = GcpProvider.ID;
        return switch (status) {
            case 404 -> new NotFoundException(message, cause, provider, code);
            case 412 -> new PreconditionFailedException(message, cause, provider, code);
            case 409 -> new AlreadyExistsException(message, cause, provider, code);
            case 401, 403 -> new AccessDeniedException(message, cause, provider, code);
            case 400, 416 -> new InvalidArgumentException(message, cause, provider, code);
            case 429 -> new ThrottledException(message, cause, provider, code, retryAfter);
            case 501 -> new UnsupportedFeatureException(message, cause, provider, code);
            // Code 0 means the request never got an HTTP response (network failure).
            case 0 -> new UnavailableException(message, cause, provider, code);
            default -> status >= 500 ? new UnavailableException(message, cause, provider, code) : null;
        };
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
