package org.kamranzafar.stratum.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The content to upload. Sealed, so the right upload strategy is chosen with an exhaustive
 * {@code switch}:
 *
 * <ul>
 *   <li>{@link Sized} content (bytes, file, stream of known length) goes to the provider in one
 *       request, or in parts when it is large.</li>
 *   <li>{@link OfUnsizedStream} content of unknown length is streamed in parts by core, so provider
 *       drivers only ever receive {@link Sized} content.</li>
 * </ul>
 */
public sealed interface BlobContent permits BlobContent.Sized, BlobContent.OfUnsizedStream {

    /** Whether the content can be read more than once, which makes the upload safe to retry. */
    boolean repeatable();

    /** Opens a stream over the content (only once for stream content). */
    InputStream openStream();

    static BlobContent ofBytes(byte[] bytes) {
        return new OfBytes(bytes.clone());
    }

    static BlobContent ofString(String text) {
        return new OfBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    static BlobContent ofFile(Path path) {
        return new OfFile(path);
    }

    /** Stream content of a known length. The stream is consumed once, so the upload is not retried. */
    static BlobContent ofStream(InputStream stream, long length) {
        return new OfStream(stream, length);
    }

    /**
     * Stream content of unknown length, uploaded in parts as it is read. Memory use is bounded by
     * {@link PutOptions#partSize()}, whatever the total size.
     */
    static BlobContent ofStream(InputStream stream) {
        return new OfUnsizedStream(stream);
    }

    /** Content whose length is known before the upload starts. */
    sealed interface Sized extends BlobContent permits OfBytes, OfFile, OfStream {

        /** Content length in bytes. */
        long length();
    }

    record OfBytes(byte[] bytes) implements Sized {
        public OfBytes {
            Objects.requireNonNull(bytes, "bytes");
        }

        @Override
        public long length() {
            return bytes.length;
        }

        @Override
        public boolean repeatable() {
            return true;
        }

        @Override
        public InputStream openStream() {
            return new ByteArrayInputStream(bytes);
        }
    }

    record OfFile(Path path) implements Sized {
        public OfFile {
            Objects.requireNonNull(path, "path");
            if (!Files.isRegularFile(path)) {
                throw new IllegalArgumentException("Not a regular file: " + path);
            }
        }

        @Override
        public long length() {
            try {
                return Files.size(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public boolean repeatable() {
            return true;
        }

        @Override
        public InputStream openStream() {
            try {
                return Files.newInputStream(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    record OfStream(InputStream stream, long length) implements Sized {
        public OfStream {
            Objects.requireNonNull(stream, "stream");
            if (length < 0) {
                throw new IllegalArgumentException("length must be >= 0");
            }
        }

        @Override
        public boolean repeatable() {
            return false;
        }

        @Override
        public InputStream openStream() {
            return stream;
        }
    }

    record OfUnsizedStream(InputStream stream) implements BlobContent {
        public OfUnsizedStream {
            Objects.requireNonNull(stream, "stream");
        }

        @Override
        public boolean repeatable() {
            return false;
        }

        @Override
        public InputStream openStream() {
            return stream;
        }
    }
}
