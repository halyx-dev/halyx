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

/** Options for a delete. */
public final class DeleteOptions {

    private static final DeleteOptions DEFAULTS = new DeleteOptions(null);

    private final ETag ifMatch;

    private DeleteOptions(ETag ifMatch) {
        this.ifMatch = ifMatch;
    }

    public static DeleteOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Delete only if the blob's current version matches. Fails with
     * {@link org.kamranzafar.halyx.error.PreconditionFailedException} if it does not, including when
     * the blob no longer exists. Requires {@link StorageCapability#CONDITIONAL_DELETE}.
     */
    public static DeleteOptions ifMatch(ETag etag) {
        return new DeleteOptions(Objects.requireNonNull(etag, "etag"));
    }

    public Optional<ETag> ifMatchETag() {
        return Optional.ofNullable(ifMatch);
    }

    public boolean isConditional() {
        return ifMatch != null;
    }
}
