package org.kamranzafar.cirrus.storage;

import java.util.Objects;
import java.util.Optional;

/** Options for a get. Built with {@link #builder()}; {@link #defaults()} for none. */
public final class GetOptions {

    private static final GetOptions DEFAULTS = builder().build();

    private final ByteRange range;
    private final ETag ifMatch;
    private final boolean validateChecksum;

    private GetOptions(Builder b) {
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
     * {@link org.kamranzafar.cirrus.error.DataIntegrityException} on a mismatch. Not available for
     * range reads.
     */
    public boolean validateChecksum() {
        return validateChecksum;
    }

    public static final class Builder {
        private ByteRange range;
        private ETag ifMatch;
        private boolean validateChecksum;

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
