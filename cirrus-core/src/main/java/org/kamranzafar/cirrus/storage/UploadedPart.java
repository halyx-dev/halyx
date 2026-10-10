package org.kamranzafar.cirrus.storage;

/**
 * A part the client uploaded with a {@linkplain BlobStore#presignUploadPart presigned part URL}.
 *
 * @param partNumber the part's number, from 1
 * @param etag       the {@code ETag} response header of the part upload. S3 and GCS need it to complete
 *                   the upload (the bucket's CORS rules must expose {@code ETag} to browsers); Azure
 *                   ignores it, so it may be null there
 */
public record UploadedPart(int partNumber, String etag) {

    public UploadedPart {
        if (partNumber < 1 || partNumber > PresignedUploads.MAX_PARTS) {
            throw new IllegalArgumentException("partNumber must be between 1 and " + PresignedUploads.MAX_PARTS);
        }
        if (etag != null && etag.isBlank()) {
            etag = null;
        }
    }
}
