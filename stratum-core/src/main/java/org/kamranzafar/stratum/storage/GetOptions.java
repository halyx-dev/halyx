package org.kamranzafar.stratum.storage;

import java.util.Objects;
import java.util.Optional;

/** Options for a get. Built with {@link #builder()}; {@link #defaults()} for none. */
public final class GetOptions {

    private static final GetOptions DEFAULTS = builder().build();

    private final ByteRange range;
    private final ETag ifMatch;

    private GetOptions(Builder b) {
        this.range = b.range;
        this.ifMatch = b.ifMatch;
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

    public static final class Builder {
        private ByteRange range;
        private ETag ifMatch;

        private Builder() {
        }

        public Builder range(ByteRange range) {
            this.range = Objects.requireNonNull(range, "range");
            return this;
        }

        public Builder ifMatch(ETag etag) {
            this.ifMatch = Objects.requireNonNull(etag, "etag");
            return this;
        }

        public GetOptions build() {
            return new GetOptions(this);
        }
    }
}
