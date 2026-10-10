package org.kamranzafar.stratum.storage;

/** Checksum algorithms providers store with blobs. Values are base64-encoded big-endian digests. */
public enum ChecksumAlgorithm {
    /** Stored by S3 (when requested) and GCS (always). */
    CRC32C,
    /** Stored by Azure (Content-MD5) and GCS (non-composite objects). */
    MD5,
    /** The S3 SDK's default upload checksum. */
    CRC32
}
