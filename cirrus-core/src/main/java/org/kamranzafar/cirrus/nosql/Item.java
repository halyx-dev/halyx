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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A stored item: its key, document, version and, if it was written with a TTL, when it expires.
 */
public record Item(Key key, Document document, Version version, Optional<Instant> expiresAt) {

    public Item {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
