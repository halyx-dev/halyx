package org.kamranzafar.stratum.storage;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Full metadata of a blob, as returned by {@code head} and {@code get}.
 *
 * @param size         total size of the blob in bytes, even when only a range was read
 * @param headers      standard HTTP headers stored with the blob
 * @param userMetadata user-defined key/value metadata. Providers may normalise key case
 * @param tier         the storage tier, when it maps to a {@link StorageTier}
 * @param checksums    full-content checksums stored by the provider, possibly none
 */
public record BlobMetadata(BlobKey key, long size, ETag etag, Instant lastModified, BlobHeaders headers,
                           Map<String, String> userMetadata, Optional<StorageTier> tier, Checksums checksums) {

    public BlobMetadata {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(etag, "etag");
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(checksums, "checksums");
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    public Optional<String> contentType() {
        return headers.contentType();
    }
}
