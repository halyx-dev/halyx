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
 * A secondary index over document fields: items are found by the value of {@code partitionAttribute} and,
 * optionally, ordered and narrowed by the string {@code sortAttribute}. Items without the partition
 * attribute are not in the index. It must exist in the store: a DynamoDB global secondary index of this name,
 * a Firestore composite index on (partition attribute, sort attribute) for collection-group queries; Cosmos DB
 * needs none beyond its default indexing.
 */
public record Index(String name, String partitionAttribute, Optional<String> sortAttribute) {

    public Index {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(partitionAttribute, "partitionAttribute");
        Objects.requireNonNull(sortAttribute, "sortAttribute");
        if (name.isBlank() || partitionAttribute.isBlank() || sortAttribute.map(String::isBlank).orElse(false)) {
            throw new IllegalArgumentException("Index name and attributes must not be blank");
        }
        if (sortAttribute.filter(partitionAttribute::equals).isPresent()) {
            throw new IllegalArgumentException("Index partition and sort attributes must differ");
        }
    }
}
