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

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One page of query results; {@link #nextPageToken()} continues the query. */
public record QueryPage(List<Item> items, Optional<String> nextPageToken) {

    public QueryPage {
        items = List.copyOf(items);
        Objects.requireNonNull(nextPageToken, "nextPageToken");
    }
}
