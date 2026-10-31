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
package dev.halyx.azure;

import com.azure.core.exception.ClientAuthenticationException;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobStorageException;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Optional;

/** Translates Azure SDK exceptions to the portable error model. Shared by all Azure service drivers. */
public final class AzureErrors {

    private static final HttpHeaderName RETRY_AFTER_MS = HttpHeaderName.fromString("x-ms-retry-after-ms");

    private AzureErrors() {
    }

    public static Optional<CloudException> translate(Throwable error) {
        String provider = AzureProvider.ID;
        if (error instanceof HttpResponseException e && e.getResponse() != null) {
            String code = e instanceof BlobStorageException b && b.getErrorCode() != null ? b.getErrorCode().toString() : null;
            return Optional.ofNullable(mapStatus(e.getResponse().getStatusCode(), e.getMessage(), e, code,
                    retryAfter(e.getResponse())));
        }
        // An Entra ID token request that failed before any service call, such as a missing managed identity.
        if (error instanceof ClientAuthenticationException) {
            return Optional.of(new AccessDeniedException(error.getMessage(), error, provider, null));
        }
        if (error instanceof UncheckedIOException || error.getCause() instanceof IOException) {
            return Optional.of(new UnavailableException(error.getMessage(), error, provider, null));
        }
        return Optional.empty();
    }

    /**
     * Maps an HTTP status from an Azure service, for drivers that make HTTP calls themselves, as {@link #translate}
     * maps the SDKs' exceptions; a status with no portable meaning is an {@link UnknownCloudException}.
     */
    public static CloudException fromStatus(int status, String message, Throwable cause, String code, Duration retryAfter) {
        CloudException mapped = mapStatus(status, message, cause, code, retryAfter);
        return mapped != null ? mapped : new UnknownCloudException(message, cause, AzureProvider.ID, code);
    }

    /** The portable error for {@code status}, or null if it has none. */
    private static CloudException mapStatus(int status, String message, Throwable cause, String code, Duration retryAfter) {
        String provider = AzureProvider.ID;
        return switch (status) {
            case 404 -> new NotFoundException(message, cause, provider, code);
            case 412 -> new PreconditionFailedException(message, cause, provider, code);
            case 409 -> new AlreadyExistsException(message, cause, provider, code);
            case 401, 403 -> new AccessDeniedException(message, cause, provider, code);
            case 400, 416 -> new InvalidArgumentException(message, cause, provider, code);
            case 501 -> new UnsupportedFeatureException(message, cause, provider, code);
            // Azure Storage signals throttling with 503 ServerBusy as well as 429.
            case 429, 503 -> new ThrottledException(message, cause, provider, code, retryAfter);
            default -> status >= 500 ? new UnavailableException(message, cause, provider, code) : null;
        };
    }

    /** Azure sends Retry-After (seconds) and, on some services, x-ms-retry-after-ms. */
    public static Duration retryAfter(HttpResponse response) {
        String millis = response.getHeaderValue(RETRY_AFTER_MS);
        if (millis != null) {
            try {
                return Duration.ofMillis(Long.parseLong(millis.trim()));
            } catch (NumberFormatException ignored) {
                // fall back to Retry-After
            }
        }
        return ThrottledException.parseRetryAfter(response.getHeaderValue(HttpHeaderName.RETRY_AFTER));
    }
}
