/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kamranzafar.halyx.aws.s3;

import org.kamranzafar.halyx.storage.StorageTier;

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
