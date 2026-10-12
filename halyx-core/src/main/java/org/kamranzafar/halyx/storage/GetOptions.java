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
import java.util.Optional;

/** Options for a get. Built with {@link #builder()}; {@link #defaults()} for none. */
public final class GetOptions {

    private static final GetOptions DEFAULTS = builder().build();

    private final ByteRange range;
    private final ETag ifMatch;
    private final boolean validateChecksum;
    private final int parallelism;
    private final int partSize;

    /** Default size of the ranges a parallel download fetches. */
    public static final int DEFAULT_PART_SIZE = 8 * 1024 * 1024;
    public static final int MIN_PART_SIZE = 1024 * 1024;
    public static final int MAX_PARALLELISM = 32;
    /** Parallelism of {@code BlobStore.download(key, path)}. */
    public static final int DEFAULT_DOWNLOAD_PARALLELISM = 4;

    private GetOptions(Builder b) {
        this.parallelism = b.parallelism;
        this.partSize = b.partSize;
        this.range = b.range;
        this.ifMatch = b.ifMatch;
        this.validateChecksum = b.validateChecksum;
    }

    public static GetOptions defaults() {
        return DEFAULTS;
    }

    public static GetOptions range(ByteRange range) {
        return builder().range(range).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Optional<ByteRange> range() {
        return Optional.ofNullable(range);
    }

    /** Read only if the current version matches. */
    public Optional<ETag> ifMatch() {
        return Optional.ofNullable(ifMatch);
    }

    /**
     * Whether to verify the downloaded bytes against the checksum stored with the blob. The check
     * happens when the stream reaches its end, which then throws
     * {@link org.kamranzafar.halyx.error.DataIntegrityException} on a mismatch. Not available for
     * range reads; works with parallel reads, since the parts are verified in order.
     */
    public boolean validateChecksum() {
        return validateChecksum;
    }

    /**
     * How many byte ranges of {@link #partSize()} are fetched at once. With more than 1, a blob larger
     * than one part is read as concurrent ranged requests, all pinned to the same version, and handed to
     * the caller in order. Memory use is about {@code parallelism * partSize}. 1 (the default) is a
     * single streamed request.
     */
    public int parallelism() {
        return parallelism;
    }

    /** Size of each range of a parallel read. */
    public int partSize() {
        return partSize;
    }

    public static final class Builder {
        private ByteRange range;
        private ETag ifMatch;
        private boolean validateChecksum;
        private int parallelism = 1;
        private int partSize = DEFAULT_PART_SIZE;

        private Builder() {
        }

        /** Ranges fetched at once, between 1 and {@link #MAX_PARALLELISM}. */
        public Builder parallelism(int parallelism) {
            if (parallelism < 1 || parallelism > MAX_PARALLELISM) {
                throw new IllegalArgumentException("parallelism must be between 1 and " + MAX_PARALLELISM);
            }
            this.parallelism = parallelism;
            return this;
        }

        /** Size of each range of a parallel read; at least {@link #MIN_PART_SIZE}. */
        public Builder partSize(int bytes) {
            if (bytes < MIN_PART_SIZE) {
                throw new IllegalArgumentException("partSize must be at least " + MIN_PART_SIZE + " bytes");
            }
            this.partSize = bytes;
            return this;
        }

        public Builder range(ByteRange range) {
            this.range = Objects.requireNonNull(range, "range");
            return this;
        }

        public Builder ifMatch(ETag etag) {
            this.ifMatch = Objects.requireNonNull(etag, "etag");
            return this;
        }

        public Builder validateChecksum() {
            this.validateChecksum = true;
            return this;
        }

        public GetOptions build() {
            if (validateChecksum && range != null) {
                throw new IllegalStateException("Checksums cover the whole blob and cannot validate a range read");
            }
            return new GetOptions(this);
        }
    }
}
