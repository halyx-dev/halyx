package org.kamranzafar.cirrus.nosql.spi;

import org.kamranzafar.cirrus.nosql.Document;
import org.kamranzafar.cirrus.nosql.DocumentUpdate;
import org.kamranzafar.cirrus.nosql.Key;
import org.kamranzafar.cirrus.nosql.Version;

import java.time.Instant;
import java.util.Optional;

/** A transaction operation as drivers see it, with conditions and expiry resolved. */
public sealed interface TransactionWrite {

    Key key();

    /** {@code expiresAt} is null for no expiry. */
    record Put(Key key, Document document, WriteCondition condition, Instant expiresAt) implements TransactionWrite {
    }

    /** The item must exist; {@code expiresAt} replaces its expiry when not null. */
    record Update(Key key, DocumentUpdate update, Optional<Version> ifVersion, Instant expiresAt) implements TransactionWrite {
    }

    /** Without {@code ifVersion}, deleting a missing item is not a failure. */
    record Delete(Key key, Optional<Version> ifVersion) implements TransactionWrite {
    }

    record Check(Key key, Version version) implements TransactionWrite {
    }
}
