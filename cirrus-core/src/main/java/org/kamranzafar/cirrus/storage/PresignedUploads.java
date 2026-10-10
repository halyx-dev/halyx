package org.kamranzafar.cirrus.storage;

/** Limits shared by every provider's presigned multipart upload. */
public final class PresignedUploads {

    /** S3 and GCS accept up to 10,000 parts (Azure 50,000); the portable limit is the lower one. */
    public static final int MAX_PARTS = 10_000;

    /** Every part except the last must be at least this big on S3 and GCS. */
    public static final int MIN_PART_SIZE = 5 * 1024 * 1024;

    private PresignedUploads() {
    }
}
