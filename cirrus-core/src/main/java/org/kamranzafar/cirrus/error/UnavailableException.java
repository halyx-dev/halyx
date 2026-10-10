package org.kamranzafar.cirrus.error;

/** A transient service or network failure. Safe to retry idempotent operations. */
public final class UnavailableException extends CloudException {

    public UnavailableException(String message) {
        this(message, null, null, null);
    }

    public UnavailableException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
