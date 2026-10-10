package org.kamranzafar.cirrus.nosql;

import java.util.Objects;
import java.util.Optional;

/**
 * An item's key: a partition key, plus a sort key for tables that have one. Items with the same partition
 * key live together and can be queried by sort-key range. Both are strings, compared lexicographically.
 */
public record Key(String partition, Optional<String> sort) {

    public Key {
        Objects.requireNonNull(partition, "partition");
        Objects.requireNonNull(sort, "sort");
        if (partition.isEmpty()) {
            throw new IllegalArgumentException("partition key must not be empty");
        }
        if (sort.isPresent() && sort.get().isEmpty()) {
            throw new IllegalArgumentException("sort key must not be empty");
        }
    }

    public static Key of(String partition) {
        return new Key(partition, Optional.empty());
    }

    public static Key of(String partition, String sort) {
        return new Key(partition, Optional.of(Objects.requireNonNull(sort, "sort")));
    }

    @Override
    public String toString() {
        return sort.map(s -> partition + "/" + s).orElse(partition);
    }
}
