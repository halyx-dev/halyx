package org.kamranzafar.cirrus.nosql;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One page of query results; {@link #nextPageToken()} continues the query. */
public record QueryPage(List<Item> items, Optional<String> nextPageToken) {

    public QueryPage {
        items = List.copyOf(items);
        Objects.requireNonNull(nextPageToken, "nextPageToken");
    }
}
