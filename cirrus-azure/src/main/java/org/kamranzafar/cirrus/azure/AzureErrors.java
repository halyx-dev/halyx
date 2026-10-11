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
package org.kamranzafar.cirrus.azure;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobStorageException;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.error.AlreadyExistsException;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.error.UnavailableException;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;

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
            String message = e.getMessage();
            CloudException mapped = switch (e.getResponse().getStatusCode()) {
                case 404 -> new NotFoundException(message, e, provider, code);
                case 412 -> new PreconditionFailedException(message, e, provider, code);
                case 409 -> new AlreadyExistsException(message, e, provider, code);
                case 401, 403 -> new AccessDeniedException(message, e, provider, code);
                case 400, 416 -> new InvalidArgumentException(message, e, provider, code);
                case 501 -> new UnsupportedFeatureException(message, e, provider, code);
                // Azure Storage signals throttling with 503 ServerBusy as well as 429.
                case 429, 503 -> new ThrottledException(message, e, provider, code, retryAfter(e.getResponse()));
                default -> e.getResponse().getStatusCode() >= 500 ? new UnavailableException(message, e, provider, code) : null;
            };
            return Optional.ofNullable(mapped);
        }
        if (error instanceof UncheckedIOException || error.getCause() instanceof IOException) {
            return Optional.of(new UnavailableException(error.getMessage(), error, provider, null));
        }
        return Optional.empty();
    }

    /** Azure sends Retry-After (seconds) and, on some services, x-ms-retry-after-ms. */
    private static Duration retryAfter(HttpResponse response) {
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
