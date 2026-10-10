package org.kamranzafar.stratum.gcp.storage;

import com.google.cloud.storage.StorageClass;
import org.kamranzafar.stratum.storage.StorageTier;

import java.util.Optional;

/** Maps portable tiers to GCS storage classes. */
final class GcsTiers {

    private GcsTiers() {
    }

    static StorageClass toNative(StorageTier tier) {
        return switch (tier) {
            case HOT -> StorageClass.STANDARD;
            case COOL -> StorageClass.NEARLINE;
            case COLD -> StorageClass.COLDLINE;
            case ARCHIVE -> StorageClass.ARCHIVE;
        };
    }

    /** Legacy classes (MULTI_REGIONAL, REGIONAL, ...) are priced and accessed like STANDARD. */
    static Optional<StorageTier> fromNative(StorageClass storageClass) {
        if (storageClass == null) {
            return Optional.empty();
        }
        if (StorageClass.NEARLINE.equals(storageClass)) {
            return Optional.of(StorageTier.COOL);
        }
        if (StorageClass.COLDLINE.equals(storageClass)) {
            return Optional.of(StorageTier.COLD);
        }
        if (StorageClass.ARCHIVE.equals(storageClass)) {
            return Optional.of(StorageTier.ARCHIVE);
        }
        return Optional.of(StorageTier.HOT);
    }
}
