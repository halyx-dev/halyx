package org.kamranzafar.stratum.error;

/** Authentication failed or the caller lacks permission for the operation. */
public final class AccessDeniedException extends CloudException {

    public AccessDeniedException(String message) {
        this(message, null, null, null);
    }

    public AccessDeniedException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }
}
