package org.kamranzafar.stratum.storage;

import java.util.Objects;

/**
 * Result of a write: the new version token of the blob and, where the provider reports them, the
 * checksums it computed over the bytes it stored.
 */
public record PutResult(BlobKey key, ETag etag, Checksums checksums) {

    public PutResult {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(etag, "etag");
        Objects.requireNonNull(checksums, "checksums");
    }

    public PutResult(BlobKey key, ETag etag) {
        this(key, etag, Checksums.none());
    }
}
