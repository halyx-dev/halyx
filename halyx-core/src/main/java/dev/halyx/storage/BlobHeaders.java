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

import java.util.Objects;
import java.util.Optional;

/**
 * Standard HTTP headers stored with a blob and returned when it is downloaded, including through
 * presigned URLs and CDNs. Supported identically by S3, Azure Blob and GCS.
 *
 * <p>Content is stored and returned exactly as uploaded: setting {@code contentEncoding("gzip")}
 * describes already-compressed bytes, and {@code get} returns those compressed bytes unchanged on
 * every provider.
 */
public record BlobHeaders(Optional<String> contentType, Optional<String> contentEncoding,
                          Optional<String> contentDisposition, Optional<String> contentLanguage,
                          Optional<String> cacheControl) {

    private static final BlobHeaders NONE = new BlobHeaders(Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty());

    public BlobHeaders {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(contentEncoding, "contentEncoding");
        Objects.requireNonNull(contentDisposition, "contentDisposition");
        Objects.requireNonNull(contentLanguage, "contentLanguage");
        Objects.requireNonNull(cacheControl, "cacheControl");
        // Providers report unset headers as null or "", so normalise both to empty.
        contentType = contentType.filter(v -> !v.isEmpty());
        contentEncoding = contentEncoding.filter(v -> !v.isEmpty());
        contentDisposition = contentDisposition.filter(v -> !v.isEmpty());
        contentLanguage = contentLanguage.filter(v -> !v.isEmpty());
        cacheControl = cacheControl.filter(v -> !v.isEmpty());
    }

    public static BlobHeaders none() {
        return NONE;
    }

    /** Builds headers from possibly-null provider values. */
    public static BlobHeaders of(String contentType, String contentEncoding, String contentDisposition,
                                 String contentLanguage, String cacheControl) {
        return new BlobHeaders(Optional.ofNullable(contentType), Optional.ofNullable(contentEncoding),
                Optional.ofNullable(contentDisposition), Optional.ofNullable(contentLanguage),
                Optional.ofNullable(cacheControl));
    }

    public BlobHeaders withContentType(String value) {
        return new BlobHeaders(Optional.of(value), contentEncoding, contentDisposition, contentLanguage, cacheControl);
    }

    public BlobHeaders withContentEncoding(String value) {
        return new BlobHeaders(contentType, Optional.of(value), contentDisposition, contentLanguage, cacheControl);
    }

    public BlobHeaders withContentDisposition(String value) {
        return new BlobHeaders(contentType, contentEncoding, Optional.of(value), contentLanguage, cacheControl);
    }

    public BlobHeaders withContentLanguage(String value) {
        return new BlobHeaders(contentType, contentEncoding, contentDisposition, Optional.of(value), cacheControl);
    }

    public BlobHeaders withCacheControl(String value) {
        return new BlobHeaders(contentType, contentEncoding, contentDisposition, contentLanguage, Optional.of(value));
    }
}
