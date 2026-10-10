package org.kamranzafar.cirrus.storage;

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
