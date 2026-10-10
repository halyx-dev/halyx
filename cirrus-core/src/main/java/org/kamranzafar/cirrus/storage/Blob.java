package org.kamranzafar.cirrus.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * A downloaded blob: its metadata plus a content stream. Always close it, ideally with
 * try-with-resources, to release the underlying connection.
 */
public final class Blob implements AutoCloseable {

    private final BlobMetadata metadata;
    private final InputStream content;

    public Blob(BlobMetadata metadata, InputStream content) {
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.content = Objects.requireNonNull(content, "content");
    }

    public BlobMetadata metadata() {
        return metadata;
    }

    public InputStream content() {
        return content;
    }

    public byte[] readAllBytes() {
        try {
            return content.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String readUtf8() {
        return new String(readAllBytes(), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        try {
            content.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
