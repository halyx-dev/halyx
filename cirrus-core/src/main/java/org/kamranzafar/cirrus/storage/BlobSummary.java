package org.kamranzafar.cirrus.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The lightweight view of a blob returned by listings. All three clouds return these fields cheaply;
 * use {@code head} for headers, checksums and user metadata.
 *
 * @param tier the storage tier, when the provider reports one that maps to a {@link StorageTier}
 */
public record BlobSummary(BlobKey key, long size, ETag etag, Instant lastModified, Optional<StorageTier> tier) {

    public BlobSummary {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(tier, "tier");
    }

    public BlobSummary(BlobKey key, long size, ETag etag, Instant lastModified) {
        this(key, size, etag, lastModified, Optional.empty());
    }
}
