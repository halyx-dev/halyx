package org.kamranzafar.cirrus.error;

/** The operation or option is not supported by this provider. See {@code Capabilities}. */
public final class UnsupportedFeatureException extends CloudException {

    public UnsupportedFeatureException(String message) {
        this(message, null, null, null);
    }

    public UnsupportedFeatureException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }
}
