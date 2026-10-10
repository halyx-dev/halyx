package org.kamranzafar.stratum.storage;

import java.time.Instant;

/**
 * The lightweight view of a blob returned by listings. All three clouds return these fields cheaply;
 * use {@code head} for content type and user metadata.
 */
public record BlobSummary(BlobKey key, long size, ETag etag, Instant lastModified) {
}
