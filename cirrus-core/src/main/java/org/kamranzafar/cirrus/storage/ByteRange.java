package org.kamranzafar.cirrus.storage;

import java.util.OptionalLong;

/**
 * A byte range to read: {@code length} bytes from {@code offset}, or to the end of the blob when
 * {@code length} is empty.
 */
public record ByteRange(long offset, OptionalLong length) {

    public ByteRange {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0");
        }
        if (length.isPresent() && length.getAsLong() <= 0) {
            throw new IllegalArgumentException("length must be > 0");
        }
    }

    public static ByteRange of(long offset, long length) {
        return new ByteRange(offset, OptionalLong.of(length));
    }

    public static ByteRange from(long offset) {
        return new ByteRange(offset, OptionalLong.empty());
    }

    /** The equivalent HTTP {@code Range} header value, such as {@code bytes=0-99}. */
    public String toHttpHeader() {
        return length.isPresent()
                ? "bytes=" + offset + "-" + (offset + length.getAsLong() - 1)
                : "bytes=" + offset + "-";
    }
}
