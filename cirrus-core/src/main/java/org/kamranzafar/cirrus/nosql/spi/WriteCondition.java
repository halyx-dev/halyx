package org.kamranzafar.cirrus.nosql.spi;

import org.kamranzafar.cirrus.nosql.Version;

import java.util.Objects;

/** The precondition of a driver write. */
public sealed interface WriteCondition {

    WriteCondition NONE = new None();

    record None() implements WriteCondition {
    }

    /** No item with the key may exist (an expired one counts as absent: see {@code expiredBefore}). */
    record NotExists() implements WriteCondition {
    }

    record IfVersion(Version version) implements WriteCondition {
        public IfVersion {
            Objects.requireNonNull(version, "version");
        }
    }
}
