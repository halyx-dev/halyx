package org.kamranzafar.stratum.storage;

import org.kamranzafar.stratum.capability.Capability;

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
    USER_METADATA
}
