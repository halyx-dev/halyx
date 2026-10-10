package org.kamranzafar.stratum.error;

/** The provider rejected the request as malformed or out of range. */
public final class InvalidArgumentException extends CloudException {

    public InvalidArgumentException(String message) {
        this(message, null, null, null);
    }

    public InvalidArgumentException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }
}
