package org.kamranzafar.cirrus.storage;

import java.util.Objects;

/** The key (object name) of a blob within a bucket. A Value Object: validated and immutable. */
public record BlobKey(String value) {

    public static final int MAX_LENGTH = 1024;

    public BlobKey {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Blob key must not be empty");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Blob key longer than " + MAX_LENGTH + " characters");
        }
        if (value.startsWith("/")) {
            throw new IllegalArgumentException("Blob key must not start with '/': " + value);
        }
    }

    public static BlobKey of(String value) {
        return new BlobKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
