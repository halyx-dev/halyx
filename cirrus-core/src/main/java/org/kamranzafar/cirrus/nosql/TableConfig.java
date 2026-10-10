package org.kamranzafar.cirrus.nosql;

import java.util.LinkedHashMap;
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

    private TableConfig(Builder b) {
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
            return new TableConfig(this);
        }
    }
}
