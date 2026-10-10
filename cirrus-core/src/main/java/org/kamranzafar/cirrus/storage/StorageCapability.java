package org.kamranzafar.cirrus.storage;

import org.kamranzafar.cirrus.capability.Capability;

/** Optional blob storage features. */
public enum StorageCapability implements Capability {
    /** {@link PutOptions#ifMatch()} and {@link PutOptions#ifNotExists()}. */
    CONDITIONAL_WRITE,
    /** {@link GetOptions#range()}. */
    RANGE_READ,
    /** Copy without downloading. Without it, {@code copy} falls back to a client-side download and upload. */
    SERVER_SIDE_COPY,
    /** Presigned download and upload URLs ({@code presign}, {@code presignDownload}, {@code presignUpload}). */
    PRESIGNED_URL,
    /** {@link PutOptions#metadata()}. */
    USER_METADATA,
    /** Checksum validation of uploads and downloads ({@code validateChecksum}). */
    CHECKSUM,
    /** {@link DeleteOptions#ifMatch(ETag)}. */
    CONDITIONAL_DELETE,
    /** Native batch deletes. Without it, {@code deleteAll} deletes keys one at a time. */
    BATCH_DELETE,
    /** {@link PutOptions#tier()} and {@code setTier}. */
    STORAGE_TIERS,
    /** Multipart uploads sent by clients through presigned part URLs ({@code startPresignedUpload}). */
    PRESIGNED_MULTIPART
}
