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
package dev.halyx.storage;

import java.util.OptionalLong;

/**
 * A byte range to read: {@code length} bytes from {@code offset}, or to the end of the blob when
 * {@code length} is empty.
 */
public record ByteRange(long offset, OptionalLong length) {

    public ByteRange {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (length.isPresent() && length.getAsLong() <= 0) {
            throw new IllegalArgumentException("length must be > 0");
        }
    }

    public static ByteRange of(long offset, long length) {
        return new ByteRange(offset, OptionalLong.of(length));
    }

    public static ByteRange from(long offset) {
        return new ByteRange(offset, OptionalLong.empty());
    }

    /** The equivalent HTTP {@code Range} header value, such as {@code bytes=0-99}. */
    public String toHttpHeader() {
        return length.isPresent()
                ? "bytes=" + offset + "-" + (offset + length.getAsLong() - 1)
                : "bytes=" + offset + "-";
    }
}
