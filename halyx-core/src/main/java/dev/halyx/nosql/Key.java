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
