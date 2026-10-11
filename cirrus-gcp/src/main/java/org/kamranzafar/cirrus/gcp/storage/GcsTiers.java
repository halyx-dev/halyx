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
package org.kamranzafar.cirrus.gcp.storage;

import com.google.cloud.storage.StorageClass;
import org.kamranzafar.cirrus.storage.StorageTier;

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
