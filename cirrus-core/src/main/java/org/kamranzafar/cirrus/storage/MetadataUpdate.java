package org.kamranzafar.cirrus.storage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * New headers and user metadata for an existing blob, applied without re-uploading its content. The
 * update <em>replaces</em> both: start from the current values to change only some of them:
 *
 * <pre>{@code
 * BlobMetadata current = store.head(key).orElseThrow();
 * store.updateMetadata(key, MetadataUpdate.from(current)
 *         .cacheControl("no-cache")
 *         .metadata("reviewed", "true")
 *         .ifMatch(current.etag())
 *         .build());
 * }</pre>
 */
public final class MetadataUpdate {

    private final BlobHeaders headers;
    private final Map<String, String> metadata;
    private final ETag ifMatch;
    private final BlobMetadata basis;

    private MetadataUpdate(Builder b) {
        this.basis = b.basis;
        this.headers = b.headers;
        this.metadata = Map.copyOf(b.metadata);
        this.ifMatch = b.ifMatch;
    }

    /** An update that clears all headers and metadata unless set on the builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** An update pre-filled with the blob's current headers and metadata. */
    public static Builder from(BlobMetadata current) {
        Builder b = new Builder().headers(current.headers()).metadata(current.userMetadata());
        b.basis = current;
        return b;
    }

    public BlobHeaders headers() {
        return headers;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    /** Apply only if the blob's current version matches. */
    public Optional<ETag> ifMatch() {
        return Optional.ofNullable(ifMatch);
    }

    /**
     * The metadata this update was built from with {@link #from}, when it still describes the blob: that
     * is, when {@link #ifMatch()} names the same version. Providers that rewrite the blob to update it use
     * this to skip a lookup.
     */
    public Optional<BlobMetadata> basis() {
        return basis != null && basis.etag().equals(ifMatch) ? Optional.of(basis) : Optional.empty();
    }

    public static final class Builder {
        private BlobHeaders headers = BlobHeaders.none();
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private ETag ifMatch;
        private BlobMetadata basis;

        private Builder() {
        }

        public Builder headers(BlobHeaders headers) {
            this.headers = Objects.requireNonNull(headers, "headers");
            return this;
        }

        public Builder contentType(String value) {
            headers = headers.withContentType(value);
            return this;
        }

        public Builder contentEncoding(String value) {
            headers = headers.withContentEncoding(value);
            return this;
        }

        public Builder contentDisposition(String value) {
            headers = headers.withContentDisposition(value);
            return this;
        }

        public Builder contentLanguage(String value) {
            headers = headers.withContentLanguage(value);
            return this;
        }

        public Builder cacheControl(String value) {
            headers = headers.withCacheControl(value);
            return this;
        }

        public Builder metadata(String key, String value) {
            metadata.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder metadata(Map<String, String> entries) {
            entries.forEach(this::metadata);
            return this;
        }

        public Builder removeMetadata(String key) {
            metadata.remove(key);
            return this;
        }

        public Builder clearMetadata() {
            metadata.clear();
            return this;
        }

        public Builder ifMatch(ETag etag) {
            this.ifMatch = Objects.requireNonNull(etag, "etag");
            return this;
        }

        public MetadataUpdate build() {
            return new MetadataUpdate(this);
        }
    }
}
