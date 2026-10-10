package org.kamranzafar.cirrus.azure.blob;

import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.rest.PagedResponse;
import com.azure.core.http.rest.Response;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.batch.BlobBatch;
import com.azure.storage.blob.batch.BlobBatchClient;
import com.azure.storage.blob.batch.BlobBatchClientBuilder;
import com.azure.storage.blob.models.BlobDownloadAsyncResponse;
import com.azure.storage.blob.models.BlobDownloadHeaders;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.DeleteSnapshotsOptionType;
import com.azure.storage.blob.models.DownloadRetryOptions;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.models.ParallelTransferOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import org.kamranzafar.cirrus.azure.AzureErrors;
import org.kamranzafar.cirrus.azure.AzureProvider;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.storage.BatchDeleteResult;
import org.kamranzafar.cirrus.storage.Blob;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobHeaders;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobMetadata;
import org.kamranzafar.cirrus.storage.BlobPage;
import org.kamranzafar.cirrus.storage.BlobSummary;
import org.kamranzafar.cirrus.storage.Checksums;
import org.kamranzafar.cirrus.storage.DeleteOptions;
import org.kamranzafar.cirrus.storage.ETag;
import org.kamranzafar.cirrus.storage.GetOptions;
import org.kamranzafar.cirrus.storage.ListOptions;
import org.kamranzafar.cirrus.storage.MetadataUpdate;
import org.kamranzafar.cirrus.storage.PresignRequest;
import org.kamranzafar.cirrus.storage.PresignedUrl;
import org.kamranzafar.cirrus.storage.PutOptions;
import org.kamranzafar.cirrus.storage.PutResult;
import org.kamranzafar.cirrus.storage.StorageCapability;
import org.kamranzafar.cirrus.storage.StorageTier;
import org.kamranzafar.cirrus.storage.spi.AbstractBlobStoreDriver;
import org.kamranzafar.cirrus.storage.spi.MultipartUploadSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Blob storage on Azure Blob Storage. A Cirrus bucket maps to a blob container. */
public final class AzureBlobStoreDriver extends AbstractBlobStoreDriver {

    private static final Duration COPY_POLL_INTERVAL = Duration.ofMillis(200);

    private final BlobServiceClient service;
    private final BlobContainerClient container;
    /** Downloads use the async client: it streams one response instead of a GET per 4 MiB chunk. */
    private final BlobContainerAsyncClient asyncContainer;
    private final boolean sharedKey;

    private static final DownloadRetryOptions DOWNLOAD_RETRIES = new DownloadRetryOptions().setMaxRetryRequests(3);

    /** Blob Batch accepts up to 256 sub-requests. */
    private static final int MAX_BATCH = 256;

    private final BlobBatchClient batch;

    public AzureBlobStoreDriver(String container, BlobServiceClient service, BlobServiceAsyncClient asyncService,
                                boolean sharedKey) {
        super(AzureProvider.ID, container, Capabilities.of(StorageCapability.values()));
        this.service = service;
        this.container = service.getBlobContainerClient(container);
        this.asyncContainer = asyncService.getBlobContainerAsyncClient(container);
        this.sharedKey = sharedKey;
        this.batch = new BlobBatchClientBuilder(service).buildClient();
    }

    @Override
    public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options, Checksums expected) {
        BinaryData data = switch (content) {
            case BlobContent.OfBytes bytes -> BinaryData.fromBytes(bytes.bytes());
            case BlobContent.OfFile file -> BinaryData.fromFile(file.path());
            case BlobContent.OfStream stream -> BinaryData.fromStream(stream.stream(), stream.length());
        };
        // The SDK uploads large content as parallel staged blocks of the configured part size.
        var headers = httpHeaders(options.headers());
        // Stored as Content-MD5; on a single-shot upload Azure also rejects content that does not match.
        expected.md5().ifPresent(md5 -> headers.setContentMd5(Base64.getDecoder().decode(md5)));
        var upload = new BlobParallelUploadOptions(data)
                .setHeaders(headers)
                .setComputeMd5(!expected.isEmpty())
                .setRequestConditions(requestConditions(options))
                .setParallelTransferOptions(new ParallelTransferOptions()
                        .setBlockSizeLong((long) options.partSize())
                        .setMaxSingleUploadSizeLong((long) options.partSize())
                        .setMaxConcurrency(options.uploadConcurrency()));
        if (!options.metadata().isEmpty()) {
            upload.setMetadata(options.metadata());
        }
        options.tier().ifPresent(tier -> upload.setTier(AzureTiers.toNative(tier)));
        try {
            var item = blob(key).uploadWithResponse(upload, null, Context.NONE).getValue();
            // For a single-shot upload this is the service's MD5 of the stored content.
            Checksums stored = item.getContentMd5() == null || content.length() > options.partSize()
                    ? Checksums.none()
                    : Checksums.of(null, Base64.getEncoder().encodeToString(item.getContentMd5()), null);
            return new PutResult(key, new ETag(item.getETag()), stored);
        } catch (BlobStorageException e) {
            // A create-only write on an existing blob is reported as 409 BlobAlreadyExists, not 412.
            if (options.ifNotExists() && BlobErrorCode.BLOB_ALREADY_EXISTS.equals(e.getErrorCode())) {
                throw new PreconditionFailedException("Blob already exists: " + key, e, provider, e.getErrorCode().toString());
            }
            throw e;
        }
    }

    @Override
    public MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options) {
        return new AzureBlockUpload(blob(key).getBlockBlobClient(), key, options);
    }

    @Override
    public Blob get(BlobKey key, GetOptions options) {
        // One streamed GET: metadata comes from the response headers. If the connection drops, the SDK
        // resumes from the last byte read, pinned to the same ETag. GET does not return the access tier,
        // so the metadata has none; head() reports it.
        BlobRange range = options.range().map(r -> r.length().isPresent()
                ? new BlobRange(r.offset(), r.length().getAsLong())
                : new BlobRange(r.offset())).orElse(null);
        var condition = new BlobRequestConditions();
        options.ifMatch().ifPresent(etag -> condition.setIfMatch(etag.value()));
        BlobDownloadAsyncResponse response = asyncContainer.getBlobAsyncClient(key.value())
                .downloadStreamWithResponse(range, DOWNLOAD_RETRIES, condition, false)
                .block();
        BlobDownloadHeaders h = response.getDeserializedHeaders();
        var metadata = new BlobMetadata(key, totalSize(h), new ETag(h.getETag()), h.getLastModified().toInstant(),
                BlobHeaders.of(h.getContentType(), h.getContentEncoding(), h.getContentDisposition(),
                        h.getContentLanguage(), h.getCacheControl()),
                h.getMetadata(), Optional.empty(), blobMd5(h, range != null));
        return new Blob(metadata, new ByteBufferStreamInputStream(response.getValue()));
    }

    /** Content-Range is "bytes start-end/total" for ranged reads; otherwise the body is the whole blob. */
    private static long totalSize(BlobDownloadHeaders h) {
        String contentRange = h.getContentRange();
        if (contentRange != null && contentRange.lastIndexOf('/') >= 0) {
            String total = contentRange.substring(contentRange.lastIndexOf('/') + 1);
            if (!total.equals("*")) {
                return Long.parseLong(total);
            }
        }
        return h.getContentLength() == null ? 0 : h.getContentLength();
    }

    /**
     * The whole blob's MD5: x-ms-blob-content-md5 on ranged responses, Content-MD5 on full ones (on a
     * ranged response Content-MD5 would be the range's, and only when asked for).
     */
    private static Checksums blobMd5(BlobDownloadHeaders h, boolean ranged) {
        byte[] md5 = h.getBlobContentMD5() != null ? h.getBlobContentMD5() : ranged ? null : h.getContentMd5();
        return md5 == null ? Checksums.none() : Checksums.of(null, Base64.getEncoder().encodeToString(md5), null);
    }

    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        try {
            return Optional.of(toMetadata(key, blob(key).getProperties()));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(BlobKey key, DeleteOptions options) {
        if (options.ifMatchETag().isEmpty()) {
            blob(key).deleteIfExists();
            return;
        }
        var conditions = new BlobRequestConditions().setIfMatch(options.ifMatchETag().get().value());
        try {
            blob(key).deleteWithResponse(DeleteSnapshotsOptionType.INCLUDE, conditions, null, Context.NONE);
        } catch (BlobStorageException e) {
            throw conditionalNotFound(key, e);
        }
    }

    @Override
    public int maxBatchSize() {
        return MAX_BATCH;
    }

    @Override
    public BatchDeleteResult deleteBatch(List<BlobKey> keys) {
        BlobBatch request = batch.getBlobBatch();
        Map<BlobKey, Response<Void>> responses = new LinkedHashMap<>();
        for (BlobKey key : keys) {
            responses.put(key, request.deleteBlob(bucket, key.value(), DeleteSnapshotsOptionType.INCLUDE, null));
        }
        batch.submitBatchWithResponse(request, false, null, Context.NONE);
        List<BlobKey> deleted = new ArrayList<>();
        Map<BlobKey, CloudException> failed = new LinkedHashMap<>();
        responses.forEach((key, response) -> {
            try {
                response.getStatusCode();
                deleted.add(key);
            } catch (BlobStorageException e) {
                if (e.getStatusCode() == 404) {
                    deleted.add(key);
                } else {
                    failed.put(key, translate(e));
                }
            }
        });
        return new BatchDeleteResult(deleted, failed);
    }

    @Override
    public BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update) {
        BlobClient blob = blob(key);
        var read = new BlobRequestConditions();
        update.ifMatch().ifPresent(etag -> read.setIfMatch(etag.value()));
        BlobProperties current;
        try {
            current = blob.getPropertiesWithResponse(read, null, Context.NONE).getValue();
        } catch (BlobStorageException e) {
            throw update.ifMatch().isPresent() ? conditionalNotFound(key, e) : e;
        }
        // Set Blob Properties replaces every HTTP header, including Content-MD5: carry the checksum over.
        var headers = httpHeaders(update.headers()).setContentMd5(current.getContentMd5());
        // Headers and metadata are two requests; chaining ETags makes a concurrent write fail the second
        // one instead of being silently overwritten.
        var afterHeaders = blob.setHttpHeadersWithResponse(headers,
                new BlobRequestConditions().setIfMatch(current.getETag()), null, Context.NONE);
        String etag = afterHeaders.getHeaders().getValue(HttpHeaderName.ETAG);
        blob.setMetadataWithResponse(update.metadata(), new BlobRequestConditions().setIfMatch(etag), null, Context.NONE);
        return toMetadata(key, blob.getProperties());
    }

    @Override
    public void setTier(BlobKey key, StorageTier tier) {
        blob(key).setAccessTier(AzureTiers.toNative(tier));
    }

    /** With a version condition, a blob that no longer exists fails the condition. */
    private RuntimeException conditionalNotFound(BlobKey key, BlobStorageException e) {
        if (e.getStatusCode() == 404) {
            return new PreconditionFailedException("No blob " + bucket + "/" + key, e, provider,
                    e.getErrorCode() == null ? null : e.getErrorCode().toString());
        }
        return e;
    }

    @Override
    public BlobPage list(ListOptions options) {
        var query = new ListBlobsOptions().setMaxResultsPerPage(options.pageSize());
        options.prefix().ifPresent(query::setPrefix);
        String token = options.pageToken().orElse(null);
        var pages = options.delimiter().isPresent()
                ? container.listBlobsByHierarchy(options.delimiter().get(), query, null).iterableByPage(token)
                : container.listBlobs(query, token, null).iterableByPage();
        try (PagedResponse<BlobItem> page = pages.iterator().next()) {
            List<BlobSummary> items = new ArrayList<>();
            List<String> prefixes = new ArrayList<>();
            for (BlobItem item : page.getValue()) {
                if (Boolean.TRUE.equals(item.isPrefix())) {
                    prefixes.add(item.getName());
                } else {
                    items.add(toSummary(item));
                }
            }
            return new BlobPage(items, prefixes, Optional.ofNullable(page.getContinuationToken()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public PutResult copy(BlobKey source, BlobKey target) {
        BlobClient from = blob(source);
        BlobClient to = blob(target);
        to.beginCopy(from.getBlobUrl(), COPY_POLL_INTERVAL).waitForCompletion();
        return new PutResult(target, new ETag(to.getProperties().getETag()));
    }

    @Override
    public PresignedUrl presign(BlobKey key, PresignRequest request) {
        BlobClient blob = blob(key);
        OffsetDateTime expiry = OffsetDateTime.now().plus(request.expiry());
        var permission = switch (request.method()) {
            case GET -> new BlobSasPermission().setReadPermission(true);
            case PUT -> new BlobSasPermission().setCreatePermission(true).setWritePermission(true);
        };
        var values = new BlobServiceSasSignatureValues(expiry, permission);
        // Shared-key accounts sign locally; Entra ID identities need a user delegation key.
        String sas = sharedKey
                ? blob.generateSas(values)
                : blob.generateUserDelegationSas(values, service.getUserDelegationKey(OffsetDateTime.now(), expiry));
        // Put Blob requires the blob type header; the content type is applied from the upload request.
        Map<String, String> headers = new LinkedHashMap<>();
        if (request.method() == PresignRequest.Method.PUT) {
            headers.put("x-ms-blob-type", "BlockBlob");
            request.contentType().ifPresent(ct -> headers.put("x-ms-blob-content-type", ct));
        }
        return new PresignedUrl(request.method(), URI.create(blob.getBlobUrl() + "?" + sas), expiry.toInstant(), headers);
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AzureErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(container, service);
    }

    private BlobClient blob(BlobKey key) {
        return container.getBlobClient(key.value());
    }

    private static BlobMetadata toMetadata(BlobKey key, BlobProperties p) {
        return new BlobMetadata(key, p.getBlobSize(), new ETag(p.getETag()), p.getLastModified().toInstant(),
                BlobHeaders.of(p.getContentType(), p.getContentEncoding(), p.getContentDisposition(),
                        p.getContentLanguage(), p.getCacheControl()),
                p.getMetadata(), AzureTiers.fromNative(p.getAccessTier()),
                p.getContentMd5() == null ? Checksums.none()
                        : Checksums.of(null, Base64.getEncoder().encodeToString(p.getContentMd5()), null));
    }

    static BlobHttpHeaders httpHeaders(BlobHeaders headers) {
        var http = new BlobHttpHeaders();
        headers.contentType().ifPresent(http::setContentType);
        headers.contentEncoding().ifPresent(http::setContentEncoding);
        headers.contentDisposition().ifPresent(http::setContentDisposition);
        headers.contentLanguage().ifPresent(http::setContentLanguage);
        headers.cacheControl().ifPresent(http::setCacheControl);
        return http;
    }

    static BlobRequestConditions requestConditions(PutOptions options) {
        var conditions = new BlobRequestConditions();
        options.ifMatch().ifPresent(etag -> conditions.setIfMatch(etag.value()));
        if (options.ifNotExists()) {
            conditions.setIfNoneMatch("*");
        }
        return conditions;
    }

    private static BlobSummary toSummary(BlobItem item) {
        BlobItemProperties p = item.getProperties();
        return new BlobSummary(BlobKey.of(item.getName()), p.getContentLength(), new ETag(p.getETag()),
                p.getLastModified().toInstant(), AzureTiers.fromNative(p.getAccessTier()));
    }
}
