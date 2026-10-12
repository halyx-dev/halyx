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
package org.kamranzafar.halyx.storage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of a listing.
 *
 * @param items         blobs on this page
 * @param prefixes      common prefixes ("directories") on this page; only with a
 *                      {@linkplain ListOptions#delimiter() delimiter}, otherwise empty
 * @param nextPageToken opaque token of the next page, if there is one
 */
public record BlobPage(List<BlobSummary> items, List<String> prefixes, Optional<String> nextPageToken) {

    public BlobPage {
        items = List.copyOf(items);
        prefixes = List.copyOf(prefixes);
        Objects.requireNonNull(nextPageToken, "nextPageToken");
        nextPageToken = nextPageToken.filter(token -> !token.isEmpty());
    }

    public BlobPage(List<BlobSummary> items, Optional<String> nextPageToken) {
        this(items, List.of(), nextPageToken);
    }

    public boolean hasNext() {
        return nextPageToken.isPresent();
    }
}
