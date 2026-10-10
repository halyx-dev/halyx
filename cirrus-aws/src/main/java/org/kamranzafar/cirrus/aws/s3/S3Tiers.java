package org.kamranzafar.cirrus.aws.s3;

import org.kamranzafar.cirrus.storage.StorageTier;

import java.util.Optional;

/** Maps portable tiers to S3 storage classes. */
final class S3Tiers {

    private S3Tiers() {
    }

    static String toNative(StorageTier tier) {
        return switch (tier) {
            case HOT -> "STANDARD";
            case COOL -> "STANDARD_IA";
            case COLD -> "GLACIER_IR";
            case ARCHIVE -> "GLACIER";
        };
    }

    /** S3 omits the storage class for STANDARD objects; classes without a portable tier map to empty. */
    static Optional<StorageTier> fromNative(String storageClass) {
        if (storageClass == null || storageClass.isEmpty()) {
            return Optional.of(StorageTier.HOT);
        }
        return switch (storageClass) {
            case "STANDARD" -> Optional.of(StorageTier.HOT);
            case "STANDARD_IA", "ONEZONE_IA" -> Optional.of(StorageTier.COOL);
            case "GLACIER_IR" -> Optional.of(StorageTier.COLD);
            case "GLACIER", "DEEP_ARCHIVE" -> Optional.of(StorageTier.ARCHIVE);
            default -> Optional.empty();
        };
    }
}
