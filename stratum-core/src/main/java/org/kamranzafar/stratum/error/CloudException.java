package org.kamranzafar.stratum.error;

import java.util.Optional;

/**
 * Root of the portable error model. Every provider translates its native SDK exceptions into one of
 * the permitted subtypes, so application code can handle failures the same way on any cloud:
 *
 * <pre>{@code
 * try {
 *     store.get(key);
 * } catch (NotFoundException e) {
 *     // same branch on S3, Azure Blob and GCS
 * }
 * }</pre>
 *
 * The hierarchy is sealed: providers cannot invent new types, so callers can rely on it being
 * complete. The native exception is always available as {@link #getCause()}.
 */
public abstract sealed class CloudException extends RuntimeException
        permits NotFoundException, AlreadyExistsException, PreconditionFailedException,
                InvalidArgumentException, AccessDeniedException, ThrottledException,
                UnavailableException, UnsupportedFeatureException, UnknownCloudException {

    private final String provider;
    private final String providerErrorCode;

    protected CloudException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause);
        this.provider = provider;
        this.providerErrorCode = providerErrorCode;
    }

    /** Whether retrying the same request may succeed (throttling, transient unavailability). */
    public boolean isRetryable() {
        return false;
    }

    /** The provider id ({@code "aws"}, {@code "azure"}, ...) that raised the error, when known. */
    public Optional<String> provider() {
        return Optional.ofNullable(provider);
    }

    /** The provider's own error code, such as {@code NoSuchKey} or {@code BlobNotFound}. */
    public Optional<String> providerErrorCode() {
        return Optional.ofNullable(providerErrorCode);
    }
}
