package org.kamranzafar.stratum.vault;

/** A non-2xx Vault API response. */
final class VaultHttpException extends RuntimeException {

    private final int status;

    VaultHttpException(int status, String message) {
        super("Vault returned " + status + ": " + message);
        this.status = status;
    }

    int status() {
        return status;
    }
}
