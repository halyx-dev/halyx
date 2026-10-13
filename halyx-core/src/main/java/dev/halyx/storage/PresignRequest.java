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
package dev.halyx.storage;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A request for a time-limited URL that lets a client without cloud credentials (a browser, a mobile
 * app, another service) download or upload one blob directly.
 *
 * @param method      {@link Method#GET} to download, {@link Method#PUT} to upload
 * @param expiry      how long the URL stays valid, at most {@link #MAX_EXPIRY}
 * @param contentType for uploads only: the content type the uploaded blob gets. Where the provider
 *                    signs it (S3, GCS), the uploader must send exactly this {@code Content-Type}
 */
public record PresignRequest(Method method, Duration expiry, Optional<String> contentType) {

    /** Seven days: the longest expiry all providers accept. */
    public static final Duration MAX_EXPIRY = Duration.ofDays(7);

    public enum Method { GET, PUT }

    public PresignRequest {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(contentType, "contentType");
        if (expiry.isNegative() || expiry.isZero() || expiry.compareTo(MAX_EXPIRY) > 0) {
            throw new IllegalArgumentException("expiry must be > 0 and <= " + MAX_EXPIRY);
        }
        if (method == Method.GET && contentType.isPresent()) {
            throw new IllegalArgumentException("contentType only applies to upload URLs");
        }
    }

    /** A URL to download the blob. */
    public static PresignRequest download(Duration expiry) {
        return new PresignRequest(Method.GET, expiry, Optional.empty());
    }

    /** A URL to upload (create or replace) the blob. */
    public static PresignRequest upload(Duration expiry) {
        return new PresignRequest(Method.PUT, expiry, Optional.empty());
    }

    /** A URL to upload the blob with the given content type. */
    public static PresignRequest upload(Duration expiry, String contentType) {
        return new PresignRequest(Method.PUT, expiry, Optional.of(Objects.requireNonNull(contentType, "contentType")));
    }
}
