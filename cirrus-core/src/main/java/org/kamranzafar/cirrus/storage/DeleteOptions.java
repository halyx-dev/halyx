package org.kamranzafar.cirrus.storage;

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
     * {@link org.kamranzafar.cirrus.error.PreconditionFailedException} if it does not, including when
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
