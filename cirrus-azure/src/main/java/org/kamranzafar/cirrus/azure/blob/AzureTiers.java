package org.kamranzafar.cirrus.azure.blob;

import com.azure.storage.blob.models.AccessTier;
import org.kamranzafar.cirrus.storage.StorageTier;

import java.util.Optional;

/** Maps portable tiers to Azure access tiers, which correspond one to one. */
final class AzureTiers {

    private AzureTiers() {
    }

    static AccessTier toNative(StorageTier tier) {
        return switch (tier) {
            case HOT -> AccessTier.HOT;
            case COOL -> AccessTier.COOL;
            case COLD -> AccessTier.COLD;
            case ARCHIVE -> AccessTier.ARCHIVE;
        };
    }

    static Optional<StorageTier> fromNative(AccessTier tier) {
        if (tier == null) {
            return Optional.empty();
        }
        if (AccessTier.HOT.equals(tier)) {
            return Optional.of(StorageTier.HOT);
        }
        if (AccessTier.COOL.equals(tier)) {
            return Optional.of(StorageTier.COOL);
        }
        if (AccessTier.COLD.equals(tier)) {
            return Optional.of(StorageTier.COLD);
        }
        if (AccessTier.ARCHIVE.equals(tier)) {
            return Optional.of(StorageTier.ARCHIVE);
        }
        return Optional.empty();
    }
}
