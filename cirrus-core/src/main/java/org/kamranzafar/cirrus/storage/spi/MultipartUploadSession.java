package org.kamranzafar.cirrus.storage.spi;

import org.kamranzafar.cirrus.storage.Checksums;
import org.kamranzafar.cirrus.storage.PutResult;

/**
 * A provider upload in parts: S3 multipart upload, Azure staged blocks, GCS resumable upload.
 * Core calls {@link #uploadPart} with part numbers 1, 2, 3, ..., possibly concurrently (up to
 * {@link #maxConcurrentParts()}) and out of order, then exactly one of {@link #complete} or
 * {@link #abort()} after every part has returned. Every part except the last is exactly the configured
 * part size. Implementations must not keep {@code data} after {@code uploadPart} returns.
 */
public interface MultipartUploadSession {

    void uploadPart(int partNumber, byte[] data, int length);

    /** How many parts may upload at once; 1 for inherently sequential uploads (a resumable stream). */
    default int maxConcurrentParts() {
        return Integer.MAX_VALUE;
    }

    /**
     * Makes the blob visible, applying the write conditions of the upload.
     *
     * @param expected checksums of the whole content when validation was requested, otherwise none
     */
    PutResult complete(Checksums expected);

    /** Discards uploaded parts. Must not throw. */
    void abort();
}
