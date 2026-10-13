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

import java.util.Objects;
import java.util.Optional;

/**
 * A listing query: an optional key prefix, an optional delimiter, a page size, and the opaque token
 * of the page to fetch. Results come back in lexicographic key order on every provider.
 *
 * <p>With a delimiter (usually {@code "/"}), keys are grouped like directories: blobs directly under
 * the prefix are returned as items, and deeper keys are rolled up into
 * {@link BlobPage#prefixes() common prefixes}:
 *
 * <pre>{@code
 * // keys: photos/2024/a.jpg, photos/2025/b.jpg, photos/index.html
 * BlobPage page = store.list(ListOptions.builder().prefix("photos/").delimiter("/").build());
 * page.items();    // [photos/index.html]
 * page.prefixes(); // [photos/2024/, photos/2025/]
 * }</pre>
 */
public final class ListOptions {

    public static final int DEFAULT_PAGE_SIZE = 1000;

    private final String prefix;
    private final String delimiter;
    private final int pageSize;
    private final String pageToken;

    private ListOptions(Builder b) {
        this.prefix = b.prefix;
        this.delimiter = b.delimiter;
        this.pageSize = b.pageSize;
        this.pageToken = b.pageToken;
    }

    public static ListOptions all() {
        return builder().build();
    }

    public static ListOptions prefix(String prefix) {
        return builder().prefix(prefix).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<String> prefix() {
        return Optional.ofNullable(prefix);
    }

    public Optional<String> delimiter() {
        return Optional.ofNullable(delimiter);
    }

    public int pageSize() {
        return pageSize;
    }

    public Optional<String> pageToken() {
        return Optional.ofNullable(pageToken);
    }

    /** The same query, positioned at the page identified by {@code token}. */
    public ListOptions withPageToken(String token) {
        Builder b = builder().pageSize(pageSize).pageToken(token);
        if (prefix != null) {
            b.prefix(prefix);
        }
        if (delimiter != null) {
            b.delimiter(delimiter);
        }
        return b.build();
    }

    public static final class Builder {
        private String prefix;
        private String delimiter;
        private int pageSize = DEFAULT_PAGE_SIZE;
        private String pageToken;

        private Builder() {
        }

        public Builder prefix(String prefix) {
            this.prefix = Objects.requireNonNull(prefix, "prefix");
            return this;
        }

        /** Groups keys by this separator, usually {@code "/"}. Page sizes count items and prefixes together. */
        public Builder delimiter(String delimiter) {
            Objects.requireNonNull(delimiter, "delimiter");
            if (delimiter.isEmpty()) {
                throw new IllegalArgumentException("delimiter must not be empty");
            }
            this.delimiter = delimiter;
            return this;
        }

        /** Page size between 1 and 1000 (the S3 maximum, so it is portable). */
        public Builder pageSize(int pageSize) {
            if (pageSize < 1 || pageSize > DEFAULT_PAGE_SIZE) {
                throw new IllegalArgumentException("pageSize must be between 1 and " + DEFAULT_PAGE_SIZE);
            }
            this.pageSize = pageSize;
            return this;
        }

        public Builder pageToken(String pageToken) {
            this.pageToken = Objects.requireNonNull(pageToken, "pageToken");
            return this;
        }

        public ListOptions build() {
            return new ListOptions(this);
        }
    }
}
