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
package org.kamranzafar.halyx.stream;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A record to send: a value, an optional key and string headers. Records with the same key go to the same
 * partition, so consumers see them in the order they were sent.
 */
public final class OutgoingRecord {

    private final String key;
    private final byte[] value;
    private final Map<String, String> headers;

    private OutgoingRecord(Builder b) {
        this.key = b.key;
        this.value = b.value;
        this.headers = Map.copyOf(b.headers);
    }

    public static OutgoingRecord of(String value) {
        return builder(value).build();
    }

    public static OutgoingRecord of(String key, String value) {
        return builder(value).key(key).build();
    }

    public static Builder builder(String value) {
        return builder(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    public static Builder builder(byte[] value) {
        return new Builder(value);
    }

    /** A copy with a different value and the same key and headers (used for claim checks and dead letters). */
    public OutgoingRecord withValue(byte[] newValue, Map<String, String> extraHeaders) {
        Builder b = new Builder(newValue);
        b.key = key;
        b.headers.putAll(headers);
        b.headers.putAll(extraHeaders);
        return b.build();
    }

    public Optional<String> key() {
        return Optional.ofNullable(key);
    }

    public byte[] value() {
        return value.clone();
    }

    /** The value without copying; callers must not modify it. */
    public byte[] valueUnsafe() {
        return value;
    }

    public int size() {
        return value.length;
    }

    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public String toString() {
        return "OutgoingRecord[key=" + key + ", size=" + value.length + "]";
    }

    public static final class Builder {
        private final byte[] value;
        private String key;
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder(byte[] value) {
            this.value = Objects.requireNonNull(value, "value").clone();
        }

        /** The key, which chooses the partition: records with the same key stay in order. */
        public Builder key(String key) {
            Objects.requireNonNull(key, "key");
            if (key.isEmpty()) {
                throw new IllegalArgumentException("key must not be empty");
            }
            this.key = key;
            return this;
        }

        /** A string header. Names starting with {@code halyx-} are reserved. */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Header names must not be blank");
            }
            headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> entries) {
            entries.forEach(this::header);
            return this;
        }

        public OutgoingRecord build() {
            return new OutgoingRecord(this);
        }
    }
}
