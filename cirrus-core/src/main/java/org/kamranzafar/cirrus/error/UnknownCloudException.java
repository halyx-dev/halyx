package org.kamranzafar.cirrus.error;

/** A provider error that could not be mapped to a more specific type. Inspect {@link #getCause()}. */
public final class UnknownCloudException extends CloudException {

    public UnknownCloudException(String message) {
        this(message, null, null, null);
    }

    public UnknownCloudException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }
}
