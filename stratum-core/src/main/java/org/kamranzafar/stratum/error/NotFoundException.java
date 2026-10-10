package org.kamranzafar.stratum.error;

/** The addressed resource (blob, bucket, key, document) does not exist. */
public final class NotFoundException extends CloudException {

    public NotFoundException(String message) {
        this(message, null, null, null);
    }

    public NotFoundException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }
}
