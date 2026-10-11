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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A partial update of top-level fields, applied atomically by the store: set values, remove fields and
 * increment numbers. The item must exist.
 */
public final class DocumentUpdate {

    private final Map<String, Object> set;
    private final Set<String> remove;
    private final Map<String, Number> increment;

    private DocumentUpdate(Builder b) {
        this.set = Map.copyOf(b.set);
        this.remove = Set.copyOf(b.remove);
        this.increment = Map.copyOf(b.increment);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Normalized values to set (see {@link Document} for the types). */
    public Map<String, Object> set() {
        return set;
    }

    public Set<String> remove() {
        return remove;
    }

    /** Amounts to add: {@code Long} or {@code Double}. A missing field counts as 0. */
    public Map<String, Number> increment() {
        return increment;
    }

    public Set<String> fields() {
        Set<String> all = new LinkedHashSet<>(set.keySet());
        all.addAll(remove);
        all.addAll(increment.keySet());
        return all;
    }

    public static final class Builder {
        private final Map<String, Object> set = new LinkedHashMap<>();
        private final Set<String> remove = new LinkedHashSet<>();
        private final Map<String, Number> increment = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder set(String field, Object value) {
            check(field);
            if (value == null) {
                throw new IllegalArgumentException("Use remove(\"" + field + "\") rather than setting it to null");
            }
            set.put(field, Document.normalize(field, value));
            return this;
        }

        public Builder remove(String field) {
            check(field);
            remove.add(field);
            return this;
        }

        public Builder increment(String field, long amount) {
            check(field);
            increment.put(field, amount);
            return this;
        }

        public Builder increment(String field, double amount) {
            check(field);
            increment.put(field, amount);
            return this;
        }

        private void check(String field) {
            Objects.requireNonNull(field, "field");
            if (field.isEmpty()) {
                throw new IllegalArgumentException("Field names must not be empty");
            }
            if (set.containsKey(field) || remove.contains(field) || increment.containsKey(field)) {
                throw new IllegalArgumentException("Field '" + field + "' is already part of this update");
            }
        }

        public DocumentUpdate build() {
            if (set.isEmpty() && remove.isEmpty() && increment.isEmpty()) {
                throw new IllegalStateException("An update needs at least one change");
            }
            return new DocumentUpdate(this);
        }
    }
}
