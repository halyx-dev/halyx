package org.kamranzafar.cirrus.nosql;

import java.util.Objects;

/**
 * An item's version, for optimistic concurrency: write with {@code WriteOptions.ifVersion(version)} and the
 * write fails if the item changed since it was read. Opaque: a Cosmos DB ETag, a Firestore update time, or a
 * counter Cirrus keeps on DynamoDB.
 */
public record Version(String value) {

    public Version {
        Objects.requireNonNull(value, "value");
    }

    @Override
    public String toString() {
        return value;
    }
}
