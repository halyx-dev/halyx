package org.kamranzafar.stratum.error;

/**
 * Data was corrupted: a checksum computed over the bytes sent or received does not match the
 * provider's. Retrying may succeed if the corruption happened in transit.
 */
public final class DataIntegrityException extends CloudException {

    public DataIntegrityException(String message) {
        this(message, null, null, null);
    }

    public DataIntegrityException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }
}
