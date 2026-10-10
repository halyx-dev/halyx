package org.kamranzafar.cirrus.storage.spi;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;
import org.kamranzafar.cirrus.storage.BatchDeleteResult;
import org.kamranzafar.cirrus.storage.Checksums;
import org.kamranzafar.cirrus.storage.DeleteOptions;
import org.kamranzafar.cirrus.storage.MetadataUpdate;
import org.kamranzafar.cirrus.storage.StorageCapability;
import org.kamranzafar.cirrus.storage.StorageTier;
import org.kamranzafar.cirrus.storage.Blob;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobMetadata;
import org.kamranzafar.cirrus.storage.BlobPage;
import org.kamranzafar.cirrus.storage.GetOptions;
import org.kamranzafar.cirrus.storage.ListOptions;
import org.kamranzafar.cirrus.storage.PresignRequest;
import org.kamranzafar.cirrus.storage.PresignedUrl;
import org.kamranzafar.cirrus.storage.PutOptions;
import org.kamranzafar.cirrus.storage.PutResult;

import java.util.List;
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

    /**
     * Uploads content of known length. Large content may be uploaded in parts internally.
     *
     * @param expected checksums core computed over the content when the caller asked for validation,
     *                 otherwise none. Drivers pass them to the provider so it rejects corrupted
     *                 uploads, and return the checksums the provider reports in the {@link PutResult}.
     */
    PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options, Checksums expected);

    /** Starts an upload in parts, used by core for streamed and unknown-length writes. */
    MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options);

    Blob get(BlobKey key, GetOptions options);

    Optional<BlobMetadata> head(BlobKey key);

    void delete(BlobKey key, DeleteOptions options);

    /** Largest batch {@link #deleteBatch} accepts; only used with {@link StorageCapability#BATCH_DELETE}. */
    default int maxBatchSize() {
        return 1;
    }

    /** Deletes up to {@link #maxBatchSize()} keys in one request. */
    default BatchDeleteResult deleteBatch(List<BlobKey> keys) {
        throw new UnsupportedFeatureException("Batch delete is not supported");
    }

    BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update);

    void setTier(BlobKey key, StorageTier tier);

    BlobPage list(ListOptions options);

    PutResult copy(BlobKey source, BlobKey target);

    PresignedUrl presign(BlobKey key, PresignRequest request);

    Capabilities capabilities();

    /** Maps any exception thrown by this driver to the portable error model. */
    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);
}
