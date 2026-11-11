/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.halyx.storage;

import dev.halyx.interceptor.InterceptorChain;

import dev.halyx.capability.Capabilities;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Portable blob storage for one bucket (S3 bucket, Azure Blob container or GCS bucket).
 *
 * <p>Instances are thread-safe and owned by their {@link dev.halyx.CloudContext}, so
 * do not close them yourself. Failures are reported as
 * {@link dev.halyx.error.CloudException} subtypes.
 */
public interface BlobStore {

    /** The provider id, such as {@code "aws"}. */
    String provider();

    String bucket();

    /**
     * The asynchronous view of this store, whose calls return {@code CompletableFuture}s; non-blocking where the
     * provider's driver has a non-blocking path. Cheap to call: Halyx' stores keep one.
     */
    default AsyncBlobStore async() {
        return async(InterceptorChain.blocking());
    }

    /**
     * The asynchronous view, running any blocking work (a call without a non-blocking path, a blocking interceptor,
     * reading a file) on {@code blocking}, such as an application's own scheduler, instead of Halyx' virtual threads.
     */
    default AsyncBlobStore async(Executor blocking) {
        return AsyncBlobStore.onExecutor(this, blocking);
    }

    default PutResult put(BlobKey key, BlobContent content) {
        return put(key, content, PutOptions.defaults());
    }

    /**
     * Uploads a blob, replacing any existing one under the key unless a condition says otherwise.
     *
     * @throws dev.halyx.error.PreconditionFailedException if a condition fails
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
     * @throws dev.halyx.error.NotFoundException if there is no blob under the key
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
     * @throws dev.halyx.error.PreconditionFailedException if a condition fails
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

    /**
     * Starts a multipart upload that a client, such as a browser, sends straight to storage: the service
     * hands out one presigned URL per part and never touches the bytes.
     *
     * <pre>{@code
     * PresignedUpload upload = store.startPresignedUpload(key, PutOptions.builder().contentType("video/mp4").build());
     * PresignedUrl part1 = store.presignUploadPart(upload, 1, Duration.ofMinutes(30));
     * // the client PUTs each part (at least 5 MiB, except the last) and reports back each response's ETag
     * store.completePresignedUpload(upload, List.of(new UploadedPart(1, etag1), new UploadedPart(2, etag2)));
     * }</pre>
     *
     * Headers, metadata and tier from {@code options} apply to the finished blob. Conditional writes and
     * checksum validation are not available, since the bytes never pass through Halyx.
     */
    PresignedUpload startPresignedUpload(BlobKey key, PutOptions options);

    /** A URL the client PUTs part {@code partNumber} (1 to {@value PresignedUploads#MAX_PARTS}) to. */
    PresignedUrl presignUploadPart(PresignedUpload upload, int partNumber, Duration expiry);

    /** Assembles the uploaded parts, in part-number order, into the blob. */
    PutResult completePresignedUpload(PresignedUpload upload, List<UploadedPart> parts);

    /** Discards the upload and any parts already sent. Idempotent. */
    void abortPresignedUpload(PresignedUpload upload);

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
     * A view of this store that seals every blob with {@code key} before it leaves the application, and opens it on the
     * way back. Each blob is HLXS sealed data bound to its blob key; reading a blob that isn't sealed through the view
     * is refused.
     *
     * <p>Sizes from {@code head} and {@code get} are the plaintext's. Sizes from {@code list} are the stored sizes,
     * slightly larger: a header of about a hundred bytes to a few KB, depending on the key, and 16 bytes per segment
     * (64 KiB by default); call {@code head} for a blob's exact size. The view drops server-side copy, since a sealed
     * blob is bound to its key, and presigned URLs, which would bypass the key: for those, use server-side encryption
     * with a key.
     */
    default BlobStore encryptedWith(CryptoKey key) {
        Objects.requireNonNull(key, "key");
        return new EncryptedBlobStore(this, blobKey -> key, key.store(), null);
    }

    /**
     * As {@link #encryptedWith(CryptoKey)}, for blob keys starting with {@code prefix} only: the rest pass through as
     * plain blobs, and keep presigned URLs. For a bucket encrypted only in part.
     */
    default BlobStore encryptedWith(CryptoKey key, String prefix) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(prefix, "prefix");
        return new EncryptedBlobStore(this, blobKey -> key, key.store(), prefix);
    }

    /**
     * As {@link #encryptedWith(CryptoKey)}, with the key for each blob named by {@code keyName}, such as a key per
     * tenant; any key of {@code keys} opens what any other sealed.
     */
    default BlobStore encryptedWith(KeyStore keys, Function<BlobKey, String> keyName) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(keyName, "keyName");
        return new EncryptedBlobStore(this, blobKey -> keys.key(keyName.apply(blobKey)), keys, null);
    }

    /** As {@link #encryptedWith(KeyStore, Function)}, for blob keys starting with {@code prefix} only. */
    default BlobStore encryptedWith(KeyStore keys, Function<BlobKey, String> keyName, String prefix) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(keyName, "keyName");
        Objects.requireNonNull(prefix, "prefix");
        return new EncryptedBlobStore(this, blobKey -> keys.key(keyName.apply(blobKey)), keys, prefix);
    }

    /**
     * A view of this store whose writes the storage service encrypts with {@code key}, an encryption key from this
     * store's provider: S3 SSE-KMS, a GCS customer-managed key, or an Azure encryption scope (give the key the option
     * {@code azure.storage.encryption-scope}). It applies to puts, uploads in parts, copies to this view, metadata and
     * tier changes, presigned upload URLs and presigned multipart uploads; a put with its own
     * {@link PutOptions#serverSideKey()} keeps that key. Reads are unchanged, and presigned download URLs keep
     * working. Requires {@link StorageCapability#SERVER_SIDE_KMS}.
     *
     * <p>Unlike {@link #encryptedWith(CryptoKey)}, the service sees the plaintext, and anyone the bucket and key
     * policies allow can read it. Use both for both.
     *
     * @throws dev.halyx.error.UnsupportedFeatureException if the store doesn't support server-side keys
     * @throws dev.halyx.error.InvalidArgumentException    if the key belongs to another provider
     */
    default BlobStore withServerSideKey(CryptoKey key) {
        Objects.requireNonNull(key, "key");
        throw new UnsupportedFeatureException("Server-side keys need the store itself; apply withServerSideKey before "
                + "other views", null, provider(), null);
    }

    /**
     * Escape hatch to the native SDK client, for features outside the portable API.
     *
     * <pre>{@code S3Client s3 = store.unwrap(S3Client.class);}</pre>
     *
     * @throws IllegalArgumentException if this provider has no client of that type
     */
    <T> T unwrap(Class<T> nativeType);
}
