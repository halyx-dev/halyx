package org.kamranzafar.stratum.azure.blob;

import com.azure.core.http.rest.PagedResponse;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.models.ParallelTransferOptions;
import com.azure.storage.blob.options.BlobInputStreamOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.blob.specialized.BlobInputStream;
import org.kamranzafar.stratum.azure.AzureErrors;
import org.kamranzafar.stratum.azure.AzureProvider;
import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
import org.kamranzafar.stratum.error.PreconditionFailedException;
import org.kamranzafar.stratum.storage.Blob;
import org.kamranzafar.stratum.storage.BlobContent;
import org.kamranzafar.stratum.storage.BlobHeaders;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.BlobMetadata;
import org.kamranzafar.stratum.storage.BlobPage;
import org.kamranzafar.stratum.storage.BlobSummary;
import org.kamranzafar.stratum.storage.ETag;
import org.kamranzafar.stratum.storage.GetOptions;
import org.kamranzafar.stratum.storage.ListOptions;
import org.kamranzafar.stratum.storage.PresignRequest;
import org.kamranzafar.stratum.storage.PresignedUrl;
import org.kamranzafar.stratum.storage.PutOptions;
import org.kamranzafar.stratum.storage.PutResult;
import org.kamranzafar.stratum.storage.StorageCapability;
import org.kamranzafar.stratum.storage.spi.AbstractBlobStoreDriver;
import org.kamranzafar.stratum.storage.spi.MultipartUploadSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Blob storage on Azure Blob Storage. A Stratum bucket maps to a blob container. */
public final class AzureBlobStoreDriver extends AbstractBlobStoreDriver {

    private static final Duration COPY_POLL_INTERVAL = Duration.ofMillis(200);

    private final BlobServiceClient service;
    private final BlobContainerClient container;
    private final boolean sharedKey;

    public AzureBlobStoreDriver(String container, BlobServiceClient service, boolean sharedKey) {
        super(AzureProvider.ID, container, Capabilities.of(StorageCapability.values()));
        this.service = service;
        this.container = service.getBlobContainerClient(container);
        this.sharedKey = sharedKey;
    }

    @Override
    public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options) {
        BinaryData data = switch (content) {
            case BlobContent.OfBytes bytes -> BinaryData.fromBytes(bytes.bytes());
            case BlobContent.OfFile file -> BinaryData.fromFile(file.path());
            case BlobContent.OfStream stream -> BinaryData.fromStream(stream.stream(), stream.length());
        };
        // The SDK uploads large content as parallel staged blocks of the configured part size.
        var upload = new BlobParallelUploadOptions(data)
                .setHeaders(httpHeaders(options.headers()))
                .setRequestConditions(requestConditions(options))
                .setParallelTransferOptions(new ParallelTransferOptions()
                        .setBlockSizeLong((long) options.partSize())
                        .setMaxSingleUploadSizeLong((long) options.partSize()));
        if (!options.metadata().isEmpty()) {
            upload.setMetadata(options.metadata());
        }
        try {
            var response = blob(key).uploadWithResponse(upload, null, Context.NONE);
            return new PutResult(key, new ETag(response.getValue().getETag()));
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
        var read = new BlobInputStreamOptions();
        options.range().ifPresent(range -> read.setRange(range.length().isPresent()
                ? new BlobRange(range.offset(), range.length().getAsLong())
                : new BlobRange(range.offset())));
        options.ifMatch().ifPresent(etag -> read.setRequestConditions(new BlobRequestConditions().setIfMatch(etag.value())));
        BlobInputStream in = blob(key).openInputStream(read);
        return new Blob(toMetadata(key, in.getProperties()), in);
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
    public void delete(BlobKey key) {
        blob(key).deleteIfExists();
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
                p.getMetadata());
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
                p.getLastModified().toInstant());
    }
}
