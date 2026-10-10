package org.kamranzafar.cirrus.error;

/** The resource being created already exists. */
public final class AlreadyExistsException extends CloudException {

    public AlreadyExistsException(String message) {
        this(message, null, null, null);
    }

    public AlreadyExistsException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }
}
