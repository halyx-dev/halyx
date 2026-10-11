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
package org.kamranzafar.cirrus.nosql;

import java.util.Objects;
import java.util.Optional;

/**
 * Items whose indexed attribute equals a value, across all partitions, in sort-attribute order. On DynamoDB,
 * index queries are eventually consistent: a write may take a moment to appear.
 */
public final class IndexQuery {

    private final String index;
    private final Object value;
    private final SortCondition condition;
    private final boolean descending;
    private final int limit;
    private final String pageToken;

    private IndexQuery(Builder b) {
        this.index = b.index;
        this.value = b.value;
        this.condition = b.condition;
        this.descending = b.descending;
        this.limit = b.limit;
        this.pageToken = b.pageToken;
    }

    public static Builder builder(String index) {
        return new Builder(index);
    }

    public String index() {
        return index;
    }

    /** The partition attribute's value: a string, number or boolean. */
    public Object value() {
        return value;
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

    public IndexQuery withPageToken(String token) {
        Builder b = new Builder(index);
        b.value = value;
        b.condition = condition;
        b.descending = descending;
        b.limit = limit;
        b.pageToken = Objects.requireNonNull(token, "token");
        return b.build();
    }

    public static final class Builder {
        private final String index;
        private Object value;
        private SortCondition condition;
        private boolean descending;
        private int limit = Query.DEFAULT_LIMIT;
        private String pageToken;

        private Builder(String index) {
            this.index = Objects.requireNonNull(index, "index");
        }

        public Builder equalTo(Object value) {
            Object normalized = Document.normalize("value", Objects.requireNonNull(value, "value"));
            if (!(normalized instanceof String || normalized instanceof Number || normalized instanceof Boolean)) {
                throw new IllegalArgumentException("Index values must be strings, numbers or booleans");
            }
            this.value = normalized;
            return this;
        }

        /** A condition on the index's sort attribute; needs an index with one. */
        public Builder where(SortCondition condition) {
            this.condition = Objects.requireNonNull(condition, "condition");
            return this;
        }

        public Builder descending() {
            this.descending = true;
            return this;
        }

        public Builder limit(int limit) {
            if (limit < 1 || limit > Query.MAX_LIMIT) {
                throw new IllegalArgumentException("limit must be between 1 and " + Query.MAX_LIMIT);
            }
            this.limit = limit;
            return this;
        }

        public Builder pageToken(String token) {
            this.pageToken = Objects.requireNonNull(token, "token");
            return this;
        }

        public IndexQuery build() {
            if (value == null) {
                throw new IllegalStateException("An index query needs equalTo(value)");
            }
            return new IndexQuery(this);
        }
    }
}
