package org.kamranzafar.stratum.error;

/** A conditional request failed, for example an ETag mismatch or a create-only write on an existing blob. */
public final class PreconditionFailedException extends CloudException {

    public PreconditionFailedException(String message) {
        this(message, null, null, null);
    }

    public PreconditionFailedException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }
}
