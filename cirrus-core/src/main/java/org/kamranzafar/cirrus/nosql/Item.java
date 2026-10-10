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
