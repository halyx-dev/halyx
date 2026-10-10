package org.kamranzafar.cirrus.storage;

import org.kamranzafar.cirrus.capability.Capabilities;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Portable blob storage for one bucket (S3 bucket, Azure Blob container or GCS bucket).
 *
 * <p>Instances are thread-safe and owned by their {@link org.kamranzafar.cirrus.CloudContext}, so
 * do not close them yourself. Failures are reported as
 * {@link org.kamranzafar.cirrus.error.CloudException} subtypes.
 */
public interface BlobStore {

    /** The provider id, such as {@code "aws"}. */
    String provider();

    String bucket();

    default PutResult put(BlobKey key, BlobContent content) {
        return put(key, content, PutOptions.defaults());
    }

    /**
     * Uploads a blob, replacing any existing one under the key unless a condition says otherwise.
     *
     * @throws org.kamranzafar.cirrus.error.PreconditionFailedException if a condition fails
     */
    PutResult put(BlobKey key, BlobContent content, PutOptions options);

    default PutResult write(BlobKey key, BlobSink sink) {
        return write(key, sink, PutOptions.defaults());
    }

    /**
     * Streams a blob of any size, uploading it in parts of {@link PutOptions#partSize()} as it is
     * written. The blob becomes visible only if {@code sink} returns normally; if it throws, the
     * upload is aborted and nothing is stored.
     *
     * <pre>{@code
     * store.write(BlobKey.of("exports/orders.csv.gz"), out -> {
     *     try (var gzip = new GZIPOutputStream(out)) {
     *         orders.forEach(order -> writeCsvLine(gzip, order));
     *     }
     * }, PutOptions.builder().contentType("text/csv").contentEncoding("gzip").build());
     * }</pre>
     *
     * @throws java.io.UncheckedIOException if {@code sink} throws an {@code IOException}
     */
    PutResult write(BlobKey key, BlobSink sink, PutOptions options);

    default Blob get(BlobKey key) {
        return get(key, GetOptions.defaults());
    }

    /**
     * Opens a blob for reading. The caller must close the returned {@link Blob}.
     *
     * @throws org.kamranzafar.cirrus.error.NotFoundException if there is no blob under the key
     */
    Blob get(BlobKey key, GetOptions options);

    /**
     * Downloads the blob to {@code target} with {@link GetOptions#DEFAULT_DOWNLOAD_PARALLELISM} concurrent
     * ranges. The file is written next to the target and moved into place, so the target only ever
     * holds a complete blob; an existing file is replaced.
     */
    default BlobMetadata download(BlobKey key, Path target) {
        return download(key, target, GetOptions.builder().parallelism(GetOptions.DEFAULT_DOWNLOAD_PARALLELISM).build());
    }

    /** Downloads the blob, or the requested range, to {@code target}; see {@link GetOptions#parallelism()}. */
    BlobMetadata download(BlobKey key, Path target, GetOptions options);

    default byte[] getBytes(BlobKey key) {
        try (Blob blob = get(key)) {
            return blob.readAllBytes();
        }
    }

    /** Metadata of the blob, or empty if it does not exist. */
    Optional<BlobMetadata> head(BlobKey key);

    default boolean exists(BlobKey key) {
        return head(key).isPresent();
    }

    /** Deletes a blob. Idempotent: deleting a missing blob is not an error. */
    default void delete(BlobKey key) {
        delete(key, DeleteOptions.defaults());
    }

    /**
     * Deletes a blob, optionally only if it is at an expected version.
     *
     * @throws org.kamranzafar.cirrus.error.PreconditionFailedException if a condition fails
     */
    void delete(BlobKey key, DeleteOptions options);

    /**
     * Deletes many blobs, using the provider's batch API where available (up to 1,000 keys per request
     * on S3, 256 on Azure, 100 on GCS). Failures are reported per key rather than thrown.
     */
    BatchDeleteResult deleteAll(Collection<BlobKey> keys);

    /**
     * Replaces a blob's headers and user metadata without re-uploading its content. The blob's
     * {@link ETag} may change (S3, Azure) or stay the same (GCS, where it tracks content generations).
     * On S3 this is a server-side copy onto itself, so it is limited to blobs of up to 5 GiB.
     *
     * @return the blob's metadata after the update
     */
    BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update);

    /**
     * Moves a blob to another storage tier without changing its content. Requires
     * {@link StorageCapability#STORAGE_TIERS}. Moving out of {@link StorageTier#ARCHIVE} on S3 needs
     * a restore first.
     */
    void setTier(BlobKey key, StorageTier tier);

    /** One page of a listing, optionally grouped by a delimiter. */
    BlobPage list(ListOptions options);

    /** Every blob under {@code prefix}, fetching pages lazily as the stream is consumed. */
    default Stream<BlobSummary> listAll(String prefix) {
        var iterator = new PagingIterator(ListOptions.prefix(prefix), this::list);
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL), false);
    }

    /**
     * Copies a blob within the bucket. Uses a server-side copy when the provider supports
     * {@link StorageCapability#SERVER_SIDE_COPY}, and a download plus upload otherwise.
     */
    PutResult copy(BlobKey source, BlobKey target);

    /**
     * A time-limited URL that lets a client without credentials download or upload the blob.
     * Requires {@link StorageCapability#PRESIGNED_URL}.
     *
     * <pre>{@code
     * PresignedUrl upload = store.presignUpload(key, Duration.ofMinutes(15), "image/png");
     * // send upload.uri() and upload.headers() to the browser, which PUTs the file directly
     * }</pre>
     */
    PresignedUrl presign(BlobKey key, PresignRequest request);

    /** A URL to download the blob. */
    default PresignedUrl presignDownload(BlobKey key, Duration expiry) {
        return presign(key, PresignRequest.download(expiry));
    }

    /** A URL to upload (create or replace) the blob. */
    default PresignedUrl presignUpload(BlobKey key, Duration expiry) {
        return presign(key, PresignRequest.upload(expiry));
    }

    /** A URL to upload the blob, which gets the given content type. */
    default PresignedUrl presignUpload(BlobKey key, Duration expiry, String contentType) {
        return presign(key, PresignRequest.upload(expiry, contentType));
    }

    Capabilities capabilities();

    /**
     * Escape hatch to the native SDK client, for features outside the portable API.
     *
     * <pre>{@code S3Client s3 = store.unwrap(S3Client.class);}</pre>
     *
     * @throws IllegalArgumentException if this provider has no client of that type
     */
    <T> T unwrap(Class<T> nativeType);
}
