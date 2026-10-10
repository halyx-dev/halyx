package org.kamranzafar.stratum.storage;

import java.util.Objects;

/**
 * An opaque version token for a blob, used for conditional requests. Compare it only with ETags from
 * the same provider. The format is provider-specific: an HTTP entity tag on S3 and Azure, and the
 * object generation on GCS.
 */
public record ETag(String value) {

    public ETag {
        Objects.requireNonNull(value, "value");
    }

    public static ETag of(String value) {
        return new ETag(value);
    }
}
