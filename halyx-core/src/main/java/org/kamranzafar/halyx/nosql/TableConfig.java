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
package org.kamranzafar.halyx.nosql;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A table (DynamoDB table, Cosmos DB container, Firestore collection) and its key attributes. The table must
 * exist with a matching key schema: partition key attribute {@code pk} by default, plus a sort key attribute
 * (such as {@code sk}) if {@link Builder#sortKey} is set. Documents may not use the key attribute names.
 */
public final class TableConfig {

    private final String name;
    private final String partitionKey;
    private final String sortKey;
    private final Map<String, String> properties;
    private final Map<String, Index> indexes;

    private TableConfig(Builder b) {
        this.indexes = Map.copyOf(b.indexes);
        this.name = b.name;
        this.partitionKey = b.partitionKey;
        this.sortKey = b.sortKey;
        this.properties = Map.copyOf(b.properties);
    }

    public static TableConfig of(String name) {
        return builder(name).build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public String partitionKey() {
        return partitionKey;
    }

    public Optional<String> sortKey() {
        return Optional.ofNullable(sortKey);
    }

    public boolean hasSortKey() {
        return sortKey != null;
    }

    /** The table's secondary indexes by name. */
    public Map<String, Index> indexes() {
        return indexes;
    }

    public Optional<Index> index(String name) {
        return Optional.ofNullable(indexes.get(name));
    }

    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, String> properties() {
        return properties;
    }

    @Override
    public String toString() {
        return "TableConfig[" + name + ", pk=" + partitionKey + (sortKey == null ? "" : ", sk=" + sortKey) + "]";
    }

    public static final class Builder {
        private final String name;
        private String partitionKey = "pk";
        private String sortKey;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final Map<String, Index> indexes = new LinkedHashMap<>();

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Table name must not be blank");
            }
            this.name = name;
        }

        public Builder partitionKey(String attribute) {
            this.partitionKey = requireName(attribute);
            return this;
        }

        public Builder sortKey(String attribute) {
            this.sortKey = requireName(attribute);
            return this;
        }

        /** A secondary index found by {@code partitionAttribute}, without ordering. */
        public Builder index(String name, String partitionAttribute) {
            return index(new Index(name, partitionAttribute, Optional.empty()));
        }

        /** A secondary index found by {@code partitionAttribute} and ordered by the string {@code sortAttribute}. */
        public Builder index(String name, String partitionAttribute, String sortAttribute) {
            return index(new Index(name, partitionAttribute, Optional.of(sortAttribute)));
        }

        public Builder index(Index index) {
            indexes.put(index.name(), Objects.requireNonNull(index, "index"));
            return this;
        }

        public Builder property(String key, String value) {
            properties.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        private static String requireName(String attribute) {
            Objects.requireNonNull(attribute, "attribute");
            if (attribute.isBlank()) {
                throw new IllegalArgumentException("Key attribute names must not be blank");
            }
            return attribute;
        }

        public TableConfig build() {
            if (partitionKey.equals(sortKey)) {
                throw new IllegalStateException("Partition and sort key attributes must differ");
            }
            for (Index index : indexes.values()) {
                for (String attribute : index.sortAttribute().map(s -> List.of(index.partitionAttribute(), s))
                        .orElse(List.of(index.partitionAttribute()))) {
                    if (attribute.equals(partitionKey) || attribute.equals(sortKey)) {
                        throw new IllegalStateException("Index " + index.name() + " must use document fields, not key "
                                + "attributes; query by key with Query instead");
                    }
                }
            }
            return new TableConfig(this);
        }
    }
}
