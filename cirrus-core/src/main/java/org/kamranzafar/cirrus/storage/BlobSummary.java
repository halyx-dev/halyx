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
package org.kamranzafar.cirrus.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The lightweight view of a blob returned by listings. All three clouds return these fields cheaply;
 * use {@code head} for headers, checksums and user metadata.
 *
 * @param tier the storage tier, when the provider reports one that maps to a {@link StorageTier}
 */
public record BlobSummary(BlobKey key, long size, ETag etag, Instant lastModified, Optional<StorageTier> tier) {

    public BlobSummary {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(tier, "tier");
    }

    public BlobSummary(BlobKey key, long size, ETag etag, Instant lastModified) {
        this(key, size, etag, lastModified, Optional.empty());
    }
}
