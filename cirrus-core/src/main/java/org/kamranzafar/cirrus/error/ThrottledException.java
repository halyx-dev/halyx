package org.kamranzafar.cirrus.error;

/** The provider is rate limiting the caller. Safe to retry with backoff. */
public final class ThrottledException extends CloudException {

    public ThrottledException(String message) {
        this(message, null, null, null);
    }

    public ThrottledException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
