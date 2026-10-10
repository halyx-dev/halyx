package org.kamranzafar.cirrus.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Options for a put or write. Built with {@link #builder()}; {@link #defaults()} for none. */
public final class PutOptions {

    /** Smallest part size: the S3 minimum for multipart uploads. */
    public static final int MIN_PART_SIZE = 5 * 1024 * 1024;
    public static final int DEFAULT_PART_SIZE = 8 * 1024 * 1024;
    /** Part sizes must be multiples of 256 KiB, the GCS resumable upload chunk granularity. */
    public static final int PART_SIZE_GRANULARITY = 256 * 1024;

    private static final PutOptions DEFAULTS = builder().build();

    private final BlobHeaders headers;
    private final Map<String, String> metadata;
    private final ETag ifMatch;
    private final boolean ifNotExists;
    private final int partSize;
    private final StorageTier tier;
    private final boolean validateChecksum;

    private PutOptions(Builder b) {
        this.tier = b.tier;
        this.validateChecksum = b.validateChecksum;
        this.headers = b.headers;
        this.metadata = Map.copyOf(b.metadata);
        this.ifMatch = b.ifMatch;
        this.ifNotExists = b.ifNotExists;
        this.partSize = b.partSize;
    }

    public static PutOptions defaults() {
        return DEFAULTS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public BlobHeaders headers() {
        return headers;
    }

    public Optional<String> contentType() {
        return headers.contentType();
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    /** Write only if the current version matches (optimistic concurrency). */
    public Optional<ETag> ifMatch() {
        return Optional.ofNullable(ifMatch);
    }

    /** Write only if no blob exists under the key (create-only). */
    public boolean ifNotExists() {
        return ifNotExists;
    }

    public boolean isConditional() {
        return ifMatch != null || ifNotExists;
    }

    /**
     * Size of each part when content is uploaded in parts (streamed writes, unknown-length streams and
     * large uploads). Also the most memory one upload buffers at a time. With S3's limit of 10,000
     * parts, the default 8 MiB allows blobs up to about 80 GiB; raise it for larger blobs.
     */
    public int partSize() {
        return partSize;
    }

    /** Storage tier of the new blob; the bucket default when empty. */
    public Optional<StorageTier> tier() {
        return Optional.ofNullable(tier);
    }

    /**
     * Whether to verify the upload end to end. Cirrus computes CRC32C and MD5 of the content, the
     * provider rejects the upload where it can check them before committing, and the checksums the
     * provider reports for the stored blob are compared afterwards. Costs one extra read of file
     * content. Requires {@link StorageCapability#CHECKSUM}.
     */
    public boolean validateChecksum() {
        return validateChecksum;
    }

    public static final class Builder {
        private BlobHeaders headers = BlobHeaders.none();
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private ETag ifMatch;
        private boolean ifNotExists;
        private int partSize = DEFAULT_PART_SIZE;
        private StorageTier tier;
        private boolean validateChecksum;

        private Builder() {
        }

        public Builder tier(StorageTier tier) {
            this.tier = Objects.requireNonNull(tier, "tier");
            return this;
        }

        public Builder validateChecksum() {
            this.validateChecksum = true;
            return this;
        }

        public Builder headers(BlobHeaders headers) {
            this.headers = Objects.requireNonNull(headers, "headers");
            return this;
        }

        public Builder contentType(String value) {
            headers = headers.withContentType(Objects.requireNonNull(value, "contentType"));
            return this;
        }

        /** Encoding of the uploaded bytes, such as {@code gzip}. The bytes are stored as given. */
        public Builder contentEncoding(String value) {
            headers = headers.withContentEncoding(Objects.requireNonNull(value, "contentEncoding"));
            return this;
        }

        /** For example {@code attachment; filename="report.pdf"} to make browsers download the blob. */
        public Builder contentDisposition(String value) {
            headers = headers.withContentDisposition(Objects.requireNonNull(value, "contentDisposition"));
            return this;
        }

        public Builder contentLanguage(String value) {
            headers = headers.withContentLanguage(Objects.requireNonNull(value, "contentLanguage"));
            return this;
        }

        /** For example {@code public, max-age=3600}, honoured by browsers and CDNs. */
        public Builder cacheControl(String value) {
            headers = headers.withCacheControl(Objects.requireNonNull(value, "cacheControl"));
            return this;
        }

        /** Adds user metadata. Use lower-case keys of letters, digits and '-' for portability. */
        public Builder metadata(String key, String value) {
            metadata.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder metadata(Map<String, String> entries) {
            entries.forEach(this::metadata);
            return this;
        }

        public Builder ifMatch(ETag etag) {
            this.ifMatch = Objects.requireNonNull(etag, "etag");
            return this;
        }

        public Builder ifNotExists() {
            this.ifNotExists = true;
            return this;
        }

        /** Part size in bytes: at least {@link #MIN_PART_SIZE} and a multiple of {@link #PART_SIZE_GRANULARITY}. */
        public Builder partSize(int bytes) {
            if (bytes < MIN_PART_SIZE || bytes % PART_SIZE_GRANULARITY != 0) {
                throw new IllegalArgumentException("partSize must be >= " + MIN_PART_SIZE + " and a multiple of "
                        + PART_SIZE_GRANULARITY + ": " + bytes);
            }
            this.partSize = bytes;
            return this;
        }

        public PutOptions build() {
            if (ifMatch != null && ifNotExists) {
                throw new IllegalStateException("ifMatch and ifNotExists are mutually exclusive");
            }
            return new PutOptions(this);
        }
    }
}
