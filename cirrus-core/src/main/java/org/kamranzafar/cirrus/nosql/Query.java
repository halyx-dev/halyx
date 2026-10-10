package org.kamranzafar.cirrus.nosql;

import java.util.Objects;
import java.util.Optional;

/**
 * Items of one partition, in sort-key order, optionally narrowed by a {@link SortCondition}. Needs a table
 * with a sort key. Results come in pages of at most {@link #limit()} items.
 */
public final class Query {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 1000;

    private final String partition;
    private final SortCondition condition;
    private final boolean descending;
    private final int limit;
    private final String pageToken;

    private Query(Builder b) {
        this.partition = b.partition;
        this.condition = b.condition;
        this.descending = b.descending;
        this.limit = b.limit;
        this.pageToken = b.pageToken;
    }

    public static Query partition(String partition) {
        return builder(partition).build();
    }

    public static Builder builder(String partition) {
        return new Builder(partition);
    }

    public String partitionKey() {
        return partition;
    }

    public Optional<SortCondition> condition() {
        return Optional.ofNullable(condition);
    }

    public boolean descending() {
        return descending;
    }

    public int limit() {
        return limit;
    }

    public Optional<String> pageToken() {
        return Optional.ofNullable(pageToken);
    }

    /** The same query, continuing from {@code token}. */
    public Query withPageToken(String token) {
        Builder b = new Builder(partition).limit(limit).pageToken(token);
        b.condition = condition;
        b.descending = descending;
        return b.build();
    }

    public static final class Builder {
        private final String partition;
        private SortCondition condition;
        private boolean descending;
        private int limit = DEFAULT_LIMIT;
        private String pageToken;

        private Builder(String partition) {
            Objects.requireNonNull(partition, "partition");
            if (partition.isEmpty()) {
                throw new IllegalArgumentException("partition must not be empty");
            }
            this.partition = partition;
        }

        public Builder where(SortCondition condition) {
            this.condition = Objects.requireNonNull(condition, "condition");
            return this;
        }

        public Builder descending() {
            this.descending = true;
            return this;
        }

        public Builder limit(int limit) {
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
            }
            this.limit = limit;
            return this;
        }

        public Builder pageToken(String token) {
            this.pageToken = Objects.requireNonNull(token, "token");
            return this;
        }

        public Query build() {
            return new Query(this);
        }
    }
}
