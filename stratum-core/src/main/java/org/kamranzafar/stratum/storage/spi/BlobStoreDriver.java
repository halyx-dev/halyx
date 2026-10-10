package org.kamranzafar.stratum.storage.spi;

import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.storage.Blob;
import org.kamranzafar.stratum.storage.BlobContent;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.BlobMetadata;
import org.kamranzafar.stratum.storage.BlobPage;
import org.kamranzafar.stratum.storage.GetOptions;
import org.kamranzafar.stratum.storage.ListOptions;
import org.kamranzafar.stratum.storage.PresignRequest;
import org.kamranzafar.stratum.storage.PresignedUrl;
import org.kamranzafar.stratum.storage.PutOptions;
import org.kamranzafar.stratum.storage.PutResult;

import java.util.Optional;

/**
 * Provider SPI for blob storage, the implementor side of the Bridge.
 *
 * <p>Drivers only translate calls to the native SDK. Argument validation, capability checks,
 * interceptors and exception translation are applied by the portable client in core, so drivers
 * should stay thin. Extend {@link AbstractBlobStoreDriver} rather than implementing this directly.
 * Application code never uses this interface.
 */
public interface BlobStoreDriver {

    /** Uploads content of known length. Large content may be uploaded in parts internally. */
    PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options);

    /** Starts an upload in parts, used by core for streamed and unknown-length writes. */
    MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options);

    Blob get(BlobKey key, GetOptions options);

    Optional<BlobMetadata> head(BlobKey key);

    void delete(BlobKey key);

    BlobPage list(ListOptions options);

    PutResult copy(BlobKey source, BlobKey target);

    PresignedUrl presign(BlobKey key, PresignRequest request);

    Capabilities capabilities();

    /** Maps any exception thrown by this driver to the portable error model. */
    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);
}
