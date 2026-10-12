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
package org.kamranzafar.halyx.storage;

import java.util.Objects;

/** The key (object name) of a blob within a bucket. A Value Object: validated and immutable. */
public record BlobKey(String value) {

    public static final int MAX_LENGTH = 1024;

    public BlobKey {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blob key must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Blob key longer than " + MAX_LENGTH + " characters");
        }
        if (value.startsWith("/")) {
            throw new IllegalArgumentException("Blob key must not start with '/': " + value);
        }
    }

    public static BlobKey of(String value) {
        return new BlobKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
