package org.kamranzafar.cirrus.storage;

import java.util.Objects;

/**
 * A multipart upload whose parts a client sends straight to storage with presigned URLs; see
 * {@link BlobStore#startPresignedUpload}. Keep it on the server (or treat it as untrusted input when a
 * client hands it back): {@code uploadId} is opaque and may encode the headers and metadata the blob
 * gets on completion.
 *
 * @param key      the blob being uploaded
 * @param uploadId the provider's upload id
 */
public record PresignedUpload(BlobKey key, String uploadId) {

    public PresignedUpload {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(uploadId, "uploadId");
        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId must not be blank");
        }
    }
}
