package org.kamranzafar.stratum.storage.spi;

import org.kamranzafar.stratum.storage.Checksums;
import org.kamranzafar.stratum.storage.PutResult;

/**
 * A provider upload in parts: S3 multipart upload, Azure staged blocks, GCS resumable upload.
 * Core calls {@link #uploadPart} sequentially with part numbers 1, 2, 3, ..., then exactly one of
 * {@link #complete()} or {@link #abort()}. Every part except the last is exactly the configured part
 * size. Implementations may reuse nothing from {@code data} after {@code uploadPart} returns.
 */
public interface MultipartUploadSession {

    void uploadPart(int partNumber, byte[] data, int length);

    /**
     * Makes the blob visible, applying the write conditions of the upload.
     *
     * @param expected checksums of the whole content when validation was requested, otherwise none
     */
    PutResult complete(Checksums expected);

    /** Discards uploaded parts. Must not throw. */
    void abort();
}
