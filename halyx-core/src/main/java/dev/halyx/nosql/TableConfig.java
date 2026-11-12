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
package dev.halyx.nosql;

import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A table (DynamoDB table, Cosmos DB container, Firestore collection) and its key attributes. The table must
 * exist with a matching key schema: partition key attribute {@code pk} by default, plus a sort key attribute
 * (such as {@code sk}) if {@link Builder#sortKey} is set. Documents may not use the key attribute names.
 * Fields named with {@link Builder#encryptedField} are encrypted on the client before they reach the store.
 */
public final class TableConfig {

    private final String name;
    private final String partitionKey;
    private final String sortKey;
    private final Map<String, String> properties;
    private final Map<String, Index> indexes;
    private final Map<String, EncryptedField> encryptedFields;

    private TableConfig(Builder b) {
        this.encryptedFields = Map.copyOf(b.encryptedFields);
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

    /** The fields encrypted on the client, by name. */
    public Map<String, EncryptedField> encryptedFields() {
        return encryptedFields;
    }

    public Optional<EncryptedField> encryptedField(String field) {
        return Optional.ofNullable(encryptedFields.get(field));
    }

    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, String> properties() {
        return properties;
    }

    /** This configuration with {@code field} encrypted too; refused as {@link Builder#build} refuses. */
    TableConfig withEncryptedField(String field, CryptoKey key) {
        Builder b = builder(name).partitionKey(partitionKey);
        if (sortKey != null) {
            b.sortKey(sortKey);
        }
        indexes.values().forEach(b::index);
        b.properties.putAll(properties);
        b.encryptedFields.putAll(encryptedFields);
        return b.encryptedField(field, key).build();
    }

    /** Configurations with the same settings are equal, so contexts can share one client per configuration. */
    @Override
    public boolean equals(Object o) {
        return o instanceof TableConfig other && Objects.equals(name, other.name)
                && Objects.equals(partitionKey, other.partitionKey)
                && Objects.equals(sortKey, other.sortKey)
                && Objects.equals(indexes, other.indexes)
                && Objects.equals(encryptedFields, other.encryptedFields)
                && Objects.equals(properties, other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, partitionKey, sortKey, indexes, encryptedFields, properties);
    }

    @Override
    public String toString() {
        return "TableConfig[" + name + ", pk=" + partitionKey + (sortKey == null ? "" : ", sk=" + sortKey)
                + (encryptedFields.isEmpty() ? "" : ", encrypted=" + new TreeSet<>(encryptedFields.keySet()))
                + "]";
    }

    public static final class Builder {
        private final String name;
        private String partitionKey = "pk";
        private String sortKey;
        private final Map<String, String> properties = new LinkedHashMap<>();
        private final Map<String, Index> indexes = new LinkedHashMap<>();
        private final Map<String, EncryptedField> encryptedFields = new LinkedHashMap<>();

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

        /**
         * Encrypts {@code field} on the client with {@code key}: the value is sealed (HLXS, AES-256-GCM) and stored as
         * binary, bound to the table, the item's key and the field's name. All encrypted fields of one document write
         * share one data key, so a write costs one key service call, and so does a read. Key and index attributes
         * can't be encrypted, and encrypted fields can't be incremented.
         */
        public Builder encryptedField(String field, CryptoKey key) {
            encryptedFields.put(requireField(field), EncryptedField.of(field, key));
            return this;
        }

        /**
         * Encrypts {@code field} with a key from {@code store} chosen per item by {@code keyName}, such as a key per
         * tenant from the partition key. Reads open the field with whichever key of {@code store} sealed it.
         */
        public Builder encryptedField(String field, KeyStore store, Function<Key, String> keyName) {
            encryptedFields.put(requireField(field), EncryptedField.of(field, store, keyName));
            return this;
        }

        private static String requireField(String field) {
            Objects.requireNonNull(field, "field");
            if (field.isEmpty()) {
                throw new IllegalArgumentException("Field names must not be empty");
            }
            return field;
        }

        public Builder property(String key, String value) {
            Objects.requireNonNull(key, "key");
            if (Objects.requireNonNull(value, "value").isBlank()) {
                properties.remove(key); // blank is unset, as an empty ${VAR:} in Spring configuration gives
            } else {
                properties.put(key, value);
            }
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
            for (String field : encryptedFields.keySet()) {
                if (field.equals(partitionKey) || field.equals(sortKey)) {
                    throw new IllegalStateException("Key attribute " + field + " can't be encrypted: the store needs it "
                            + "in the clear to find the item");
                }
                for (Index index : indexes.values()) {
                    if (field.equals(index.partitionAttribute()) || index.sortAttribute().filter(field::equals).isPresent()) {
                        throw new IllegalStateException("Field " + field + " is an attribute of index " + index.name()
                                + " and can't be encrypted: the store needs it in the clear to query");
                    }
                }
            }
            return new TableConfig(this);
        }
    }
}
