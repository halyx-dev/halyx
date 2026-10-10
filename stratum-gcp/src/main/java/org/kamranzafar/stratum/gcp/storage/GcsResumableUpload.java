package org.kamranzafar.stratum.gcp.storage;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.BlobWriteSession;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobWriteOption;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.ETag;
import org.kamranzafar.stratum.storage.PutResult;
import org.kamranzafar.stratum.storage.spi.MultipartUploadSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.ExecutionException;

/**
 * A GCS resumable upload. Data is streamed to the session as parts arrive; the object (with its
 * write preconditions) is created only when the channel is closed in {@link #complete()}. An
 * abandoned session is never finalised and expires on the service side.
 */
final class GcsResumableUpload implements MultipartUploadSession {

    private final BlobKey key;
    private final BlobWriteSession session;
    private final WritableByteChannel channel;

    GcsResumableUpload(Storage storage, BlobInfo info, BlobWriteOption[] conditions, BlobKey key) {
        this.key = key;
        this.session = storage.blobWriteSession(info, conditions);
        try {
            this.channel = session.open();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void uploadPart(int partNumber, byte[] data, int length) {
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public PutResult complete() {
        try {
            channel.close();
            BlobInfo written = session.getResult().get();
            return new PutResult(key, new ETag(String.valueOf(written.getGeneration())));
        } catch (IOException e) {
            throw unwrap(e);
        } catch (ExecutionException e) {
            throw unwrap(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while finishing upload of " + key, e);
        }
    }

    @Override
    public void abort() {
        // Not closing the channel leaves the resumable session unfinalised: no object is created.
    }

    /** The GCS client reports failures (e.g. 412) wrapped in IOException or ExecutionException. */
    private static RuntimeException unwrap(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof RuntimeException runtime) {
                return runtime;
            }
        }
        return error instanceof IOException io ? new UncheckedIOException(io) : new IllegalStateException(error);
    }
}
