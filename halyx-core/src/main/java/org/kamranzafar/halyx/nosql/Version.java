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

import java.util.Objects;

/**
 * An item's version, for optimistic concurrency: write with {@code WriteOptions.ifVersion(version)} and the
 * write fails if the item changed since it was read. Opaque: a Cosmos DB ETag, a Firestore update time, or a
 * counter Halyx keeps on DynamoDB.
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
