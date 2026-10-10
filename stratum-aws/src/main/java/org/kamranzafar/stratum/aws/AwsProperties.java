package org.kamranzafar.stratum.aws;

/** AWS-specific keys for {@link org.kamranzafar.stratum.config.ProviderConfig#property(String, String)}. */
public final class AwsProperties {

    /** {@code "true"} to use path-style S3 addressing, which emulators and some S3-compatible stores need. */
    public static final String S3_PATH_STYLE = "aws.s3.path-style";

    /**
     * Uploads of known length above this many bytes use S3 multipart upload. Default 64 MiB; a single
     * S3 PUT is limited to 5 GiB.
     */
    public static final String S3_MULTIPART_THRESHOLD = "aws.s3.multipart-threshold";

    public static final long DEFAULT_MULTIPART_THRESHOLD = 64L * 1024 * 1024;

    private AwsProperties() {
    }
}
