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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A portable document: string field names mapped to values that every store can hold.
 *
 * <ul>
 *   <li>{@code String}, {@code Boolean}, {@code byte[]} (binary) and {@code null}</li>
 *   <li>numbers: {@code Long} (from any integral type), {@code Double} (from {@code float}/{@code double}) and
 *       {@code BigDecimal}. DynamoDB keeps decimals exactly; Cosmos DB and Firestore store them as doubles</li>
 *   <li>{@code List} of values and nested {@code Document}s (or {@code Map<String, ?>}, converted)</li>
 * </ul>
 *
 * Documents are immutable and compare by value (binary values by content).
 */
public final class Document {

    private static final Document EMPTY = new Document(Map.of());

    private final Map<String, Object> fields;

    private Document(Map<String, Object> fields) {
        this.fields = fields;
    }

    public static Document empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A document from a map of supported values; nested maps become documents. */
    public static Document of(Map<String, ?> fields) {
        Builder b = builder();
        fields.forEach(b::put);
        return b.build();
    }

    public Builder toBuilder() {
        Builder b = builder();
        fields.forEach(b::put);
        return b;
    }

    public Set<String> fieldNames() {
        return fields.keySet();
    }

    public boolean has(String field) {
        return fields.containsKey(field);
    }

    public int size() {
        return fields.size();
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    /** The normalized value, or null. */
    public Object get(String field) {
        Object value = fields.get(field);
        return value instanceof byte[] bytes ? bytes.clone() : value;
    }

    public Optional<String> getString(String field) {
        return typed(field, String.class);
    }

    public Optional<Long> getLong(String field) {
        Object value = fields.get(field);
        return switch (value) {
            case null -> Optional.empty();
            case Long l -> Optional.of(l);
            case Double d when d == Math.rint(d) && !Double.isInfinite(d) -> Optional.of(d.longValue());
            case BigDecimal bd -> Optional.of(bd.longValueExact());
            default -> throw new ClassCastException(field + " is " + value.getClass().getSimpleName() + ", not a whole number");
        };
    }

    public Optional<Double> getDouble(String field) {
        Object value = fields.get(field);
        return switch (value) {
            case null -> Optional.empty();
            case Number n -> Optional.of(n.doubleValue());
            default -> throw new ClassCastException(field + " is " + value.getClass().getSimpleName() + ", not a number");
        };
    }

    public Optional<BigDecimal> getDecimal(String field) {
        Object value = fields.get(field);
        return switch (value) {
            case null -> Optional.empty();
            case BigDecimal bd -> Optional.of(bd);
            case Long l -> Optional.of(BigDecimal.valueOf(l));
            case Double d -> Optional.of(BigDecimal.valueOf(d));
            default -> throw new ClassCastException(field + " is " + value.getClass().getSimpleName() + ", not a number");
        };
    }

    public Optional<Boolean> getBoolean(String field) {
        return typed(field, Boolean.class);
    }

    public Optional<byte[]> getBinary(String field) {
        return typed(field, byte[].class).map(byte[]::clone);
    }

    public Optional<Document> getDocument(String field) {
        return typed(field, Document.class);
    }

    @SuppressWarnings("unchecked")
    public Optional<List<Object>> getList(String field) {
        return typed(field, List.class).map(l -> (List<Object>) l);
    }

    private <T> Optional<T> typed(String field, Class<T> type) {
        Object value = fields.get(field);
        if (value == null) {
            return Optional.empty();
        }
        if (!type.isInstance(value)) {
            throw new ClassCastException(field + " is " + value.getClass().getSimpleName() + ", not " + type.getSimpleName());
        }
        return Optional.of(type.cast(value));
    }

    /** The fields as plain Java values: nested documents become maps. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        fields.forEach((k, v) -> out.put(k, plain(v)));
        return out;
    }

    private static Object plain(Object value) {
        return switch (value) {
            case Document d -> d.toMap();
            case List<?> list -> list.stream().map(Document::plain).toList();
            case byte[] bytes -> bytes.clone();
            case null -> null;
            default -> value;
        };
    }

    /** Normalizes a value to the supported types, rejecting anything else. */
    static Object normalize(String path, Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Boolean b -> b;
            case Long l -> l;
            case Integer i -> i.longValue();
            case Short s -> s.longValue();
            case Byte b -> b.longValue();
            case Double d -> requireFinite(path, d);
            case Float f -> requireFinite(path, f.doubleValue());
            case BigDecimal bd -> bd;
            case java.math.BigInteger bi -> new BigDecimal(bi);
            case byte[] bytes -> bytes.clone();
            case Document d -> d;
            case Map<?, ?> map -> {
                Builder nested = builder();
                map.forEach((k, v) -> {
                    if (!(k instanceof String key)) {
                        throw new IllegalArgumentException(path + " has a non-string key: " + k);
                    }
                    nested.put(key, v);
                });
                yield nested.build();
            }
            case List<?> list -> {
                List<Object> copy = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    copy.add(normalize(path + "[" + i + "]", list.get(i)));
                }
                yield Collections.unmodifiableList(copy);
            }
            default -> throw new IllegalArgumentException(path + " has unsupported type " + value.getClass().getName()
                    + "; use String, a number, Boolean, byte[], List, Map or Document");
        };
    }

    private static Double requireFinite(String path, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException(path + " is " + d + ", which no store can hold");
        }
        return d;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Document other && deepEquals(fields, other.fields);
    }

    private static boolean deepEquals(Object a, Object b) {
        if (a instanceof byte[] x && b instanceof byte[] y) {
            return Arrays.equals(x, y);
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (!x.keySet().equals(y.keySet())) {
                return false;
            }
            for (Object k : x.keySet()) {
                if (!deepEquals(x.get(k), y.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (!deepEquals(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    @Override
    public int hashCode() {
        return fields.keySet().hashCode();
    }

    @Override
    public String toString() {
        return "Document" + toMap();
    }

    public static final class Builder {
        private final Map<String, Object> fields = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder put(String field, Object value) {
            Objects.requireNonNull(field, "field");
            if (field.isEmpty()) {
                throw new IllegalArgumentException("Field names must not be empty");
            }
            fields.put(field, normalize(field, value));
            return this;
        }

        public Builder remove(String field) {
            fields.remove(field);
            return this;
        }

        public Document build() {
            return fields.isEmpty() ? EMPTY : new Document(Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
        }
    }
}
