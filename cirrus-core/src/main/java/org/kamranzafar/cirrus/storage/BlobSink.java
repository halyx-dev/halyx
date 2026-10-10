package org.kamranzafar.cirrus.storage;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes blob content to a stream, for {@link BlobStore#write}. The stream is managed by Cirrus:
 * the blob is committed only after this method returns normally, and discarded if it throws.
 * Closing the stream (for example by a wrapping {@code GZIPOutputStream}) does not commit it.
 */
@FunctionalInterface
public interface BlobSink {

    void writeTo(OutputStream out) throws IOException;
}
