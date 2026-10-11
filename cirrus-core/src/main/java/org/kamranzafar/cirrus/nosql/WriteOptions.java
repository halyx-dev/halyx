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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Conditions and expiry for a write. */
public final class WriteOptions {

    private static final WriteOptions DEFAULTS = builder().build();

    private final Version ifVersion;
    private final boolean ifNotExists;
    private final Duration ttl;

    private WriteOptions(Builder b) {
        this.ifVersion = b.ifVersion;
        this.ifNotExists = b.ifNotExists;
        this.ttl = b.ttl;
    }

    public static WriteOptions defaults() {
        return DEFAULTS;
    }

    public static WriteOptions ifVersion(Version version) {
        return builder().ifVersion(version).build();
    }

    public static WriteOptions ifNotExists() {
        return builder().ifNotExists().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Write only if the item is still at this version; otherwise {@code PreconditionFailedException}. */
    public Optional<Version> version() {
        return Optional.ofNullable(ifVersion);
    }

    /** Write only if no (unexpired) item has the key; otherwise {@code PreconditionFailedException}. */
    public boolean notExists() {
        return ifNotExists;
    }

    /**
     * The item expires this long after the write. Expired items are never returned; the store deletes them
     * eventually (DynamoDB and Firestore within a day or two, Cosmos DB promptly). The table must have TTL
     * enabled on the attribute Cirrus uses (see the provider's documentation).
     */
    public Optional<Duration> ttl() {
        return Optional.ofNullable(ttl);
    }

    public static final class Builder {
        private Version ifVersion;
        private boolean ifNotExists;
        private Duration ttl;

        private Builder() {
        }

        public Builder ifVersion(Version version) {
            this.ifVersion = Objects.requireNonNull(version, "version");
            return this;
        }

        public Builder ifNotExists() {
            this.ifNotExists = true;
            return this;
        }

        public Builder ttl(Duration ttl) {
            Objects.requireNonNull(ttl, "ttl");
            if (ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("ttl must be positive");
            }
            this.ttl = ttl;
            return this;
        }

        public WriteOptions build() {
            if (ifVersion != null && ifNotExists) {
                throw new IllegalStateException("ifVersion and ifNotExists cannot be combined");
            }
            return new WriteOptions(this);
        }
    }

    Instant expiresAt(Instant now) {
        return ttl == null ? null : now.plus(ttl);
    }
}
