package org.kamranzafar.stratum.storage;

/** Result of a write: the new version token of the blob. */
public record PutResult(BlobKey key, ETag etag) {
}
