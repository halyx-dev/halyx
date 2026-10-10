package org.kamranzafar.cirrus.gcp.storage;

import com.google.auth.ServiceAccountSigner;
import com.google.cloud.ReadChannel;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.HttpStorageOptions;
import com.google.cloud.storage.MultipartUploadClient;
import com.google.cloud.storage.MultipartUploadSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobListOption;
import com.google.cloud.storage.Storage.BlobSourceOption;
import com.google.cloud.storage.Storage.BlobTargetOption;
import com.google.cloud.storage.StorageBatch;
import com.google.cloud.storage.StorageBatchResult;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.Storage.BlobWriteOption;
import com.google.cloud.storage.Storage.CopyRequest;
import com.google.cloud.storage.Storage.SignUrlOption;
import com.google.cloud.storage.multipartupload.model.AbortMultipartUploadRequest;
import com.google.cloud.storage.multipartupload.model.CompleteMultipartUploadRequest;
import com.google.cloud.storage.multipartupload.model.CompletedMultipartUpload;
import com.google.cloud.storage.multipartupload.model.CompletedPart;
import com.google.cloud.storage.multipartupload.model.CreateMultipartUploadRequest;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.gcp.GcpErrors;
import org.kamranzafar.cirrus.gcp.GcpProvider;
import org.kamranzafar.cirrus.storage.BatchDeleteResult;
import org.kamranzafar.cirrus.storage.Blob;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobHeaders;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobMetadata;
import org.kamranzafar.cirrus.storage.BlobPage;
import org.kamranzafar.cirrus.storage.BlobSummary;
import org.kamranzafar.cirrus.storage.ByteRange;
import org.kamranzafar.cirrus.storage.Checksums;
import org.kamranzafar.cirrus.storage.DeleteOptions;
import org.kamranzafar.cirrus.storage.ETag;
import org.kamranzafar.cirrus.storage.GetOptions;
import org.kamranzafar.cirrus.storage.ListOptions;
import org.kamranzafar.cirrus.storage.MetadataUpdate;
import org.kamranzafar.cirrus.storage.PresignRequest;
import org.kamranzafar.cirrus.storage.PresignedUpload;
import org.kamranzafar.cirrus.storage.PresignedUrl;
import org.kamranzafar.cirrus.storage.PutOptions;
import org.kamranzafar.cirrus.storage.PutResult;
import org.kamranzafar.cirrus.storage.StorageCapability;
import org.kamranzafar.cirrus.storage.StorageTier;
import org.kamranzafar.cirrus.storage.UploadedPart;
import org.kamranzafar.cirrus.storage.spi.AbstractBlobStoreDriver;
import org.kamranzafar.cirrus.storage.spi.MultipartUploadSession;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Blob storage on Google Cloud Storage.
 *
 * <p>The Cirrus {@link ETag} of a GCS object is its <em>generation</em> number, not the HTTP etag,
 * because GCS write preconditions are expressed on generations. This keeps {@code ifMatch} working the
 * same way as on S3 and Azure.
 */
public final class GcsBlobStoreDriver extends AbstractBlobStoreDriver {

    /** Bytes downloaded ahead while the metadata lookup is in flight. */
    private static final int PREFETCH_BYTES = 256 * 1024;

    private final Storage storage;
    private final boolean customEndpoint;
    /** The XML API's S3-style multipart uploads, which (unlike resumable uploads) take parts in any order. */
    private volatile MultipartUploadClient multipart;

    /** GCS JSON batch requests carry at most 100 calls. */
    private static final int MAX_BATCH = 100;

    public GcsBlobStoreDriver(String bucket, Storage storage, boolean customEndpoint) {
        super(GcpProvider.ID, bucket, capabilities(storage));
        this.storage = storage;
        this.customEndpoint = customEndpoint;
    }

    @Override
    public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options, Checksums expected) {
        BlobInfo info = blobInfo(key, options);
        List<BlobWriteOption> conditions = new ArrayList<>(List.of(writeConditions(options)));
        if (!expected.isEmpty()) {
            // GCS rejects the upload before committing if the content does not match.
            BlobInfo.Builder checked = info.toBuilder();
            expected.crc32c().ifPresent(checked::setCrc32c);
            expected.md5().ifPresent(checked::setMd5);
            info = checked.build();
            expected.crc32c().ifPresent(crc -> conditions.add(BlobWriteOption.crc32cMatch()));
            expected.md5().ifPresent(md5 -> conditions.add(BlobWriteOption.md5Match()));
        }
        // createFrom uses a resumable upload in chunks of the part size, so large content is fine.
        try (InputStream in = content.openStream()) {
            com.google.cloud.storage.Blob written = storage.createFrom(info, in, options.partSize(),
                    conditions.toArray(BlobWriteOption[]::new));
            return new PutResult(key, etagOf(written), checksumsOf(written));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options) {
        return new GcsResumableUpload(storage, blobInfo(key, options), writeConditions(options), key);
    }

    private BlobInfo blobInfo(BlobKey key, PutOptions options) {
        Long expectedGeneration = options.ifNotExists() ? Long.valueOf(0L)
                : options.ifMatch().map(etag -> generation(key, etag)).orElse(null);
        BlobInfo.Builder info = BlobInfo.newBuilder(BlobId.of(bucket, key.value(), expectedGeneration));
        var headers = options.headers();
        headers.contentType().ifPresent(info::setContentType);
        headers.contentEncoding().ifPresent(info::setContentEncoding);
        headers.contentDisposition().ifPresent(info::setContentDisposition);
        headers.contentLanguage().ifPresent(info::setContentLanguage);
        headers.cacheControl().ifPresent(info::setCacheControl);
        if (!options.metadata().isEmpty()) {
            info.setMetadata(options.metadata());
        }
        options.tier().ifPresent(tier -> info.setStorageClass(GcsTiers.toNative(tier)));
        return info.build();
    }

    /** Generation 0 in the blob id means "must not exist"; any other value "must currently be this generation". */
    private static BlobWriteOption[] writeConditions(PutOptions options) {
        return options.isConditional()
                ? new BlobWriteOption[] {BlobWriteOption.generationMatch()}
                : new BlobWriteOption[0];
    }

    @Override
    public Blob get(BlobKey key, GetOptions options) {
        Long expected = options.ifMatch().map(etag -> generationOf(key, etag)).orElse(null);
        if (expected != null) {
            return getGeneration(key, expected, options);
        }
        com.google.cloud.storage.Blob blob = storage.get(BlobId.of(bucket, key.value()));
        if (blob == null) {
            throw new NotFoundException("No blob " + bucket + "/" + key, null, provider, "notFound");
        }
        // Pin the generation we just read metadata for, so content and metadata always agree.
        return new Blob(toMetadata(key, blob), open(key, blob.getGeneration(), options));
    }

    /**
     * With a known generation, the content download starts at once, overlapping the metadata lookup:
     * both are pinned to that generation, so they agree without a lookup first.
     */
    private Blob getGeneration(BlobKey key, long generation, GetOptions options) {
        InputStream content = new PrefetchingInputStream(open(key, generation, options), PREFETCH_BYTES);
        try {
            com.google.cloud.storage.Blob blob = storage.get(BlobId.of(bucket, key.value(), generation));
            if (blob == null) {
                if (storage.get(BlobId.of(bucket, key.value())) == null) {
                    throw new NotFoundException("No blob " + bucket + "/" + key, null, provider, "notFound");
                }
                throw new PreconditionFailedException("Generation mismatch for " + key, null, provider,
                        "conditionNotMet");
            }
            return new Blob(toMetadata(key, blob), content);
        } catch (RuntimeException e) {
            closeQuietly(content);
            throw e;
        }
    }

    /** Content only, pinned to the generation: a version that no longer exists fails on the first read. */
    @Override
    public InputStream readRange(BlobKey key, ETag version, long offset, long length) {
        return open(key, generationOf(key, version), GetOptions.range(ByteRange.of(offset, length)));
    }

    private Long generationOf(BlobKey key, ETag etag) {
        try {
            return Long.parseLong(etag.value());
        } catch (NumberFormatException e) {
            throw new PreconditionFailedException("Generation mismatch for " + key, null, provider, "conditionNotMet");
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException | RuntimeException ignored) {
            // nothing to recover
        }
    }

    /** Raw bytes: never transcode gzip-encoded objects, matching S3 and Azure. */
    private InputStream open(BlobKey key, long generation, GetOptions options) {
        ReadChannel reader = storage.reader(BlobId.of(bucket, key.value(), generation),
                Storage.BlobSourceOption.shouldReturnRawInputStream(true));
        try {
            if (options.range().isPresent()) {
                var range = options.range().get();
                reader.seek(range.offset());
                if (range.length().isPresent()) {
                    reader = reader.limit(range.offset() + range.length().getAsLong());
                }
            }
        } catch (IOException e) {
            reader.close();
            throw new UncheckedIOException(e);
        }
        return Channels.newInputStream(reader);
    }

    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        return Optional.ofNullable(storage.get(BlobId.of(bucket, key.value()))).map(b -> toMetadata(key, b));
    }

    @Override
    public void delete(BlobKey key, DeleteOptions options) {
        if (options.ifMatchETag().isEmpty()) {
            storage.delete(BlobId.of(bucket, key.value()));
            return;
        }
        long generation = generation(key, options.ifMatchETag().get());
        boolean deleted = storage.delete(BlobId.of(bucket, key.value(), generation), BlobSourceOption.generationMatch());
        if (!deleted) {
            throw new PreconditionFailedException("No blob " + bucket + "/" + key + " at generation " + generation,
                    null, provider, "notFound");
        }
    }

    @Override
    public int maxBatchSize() {
        return MAX_BATCH;
    }

    @Override
    public BatchDeleteResult deleteBatch(List<BlobKey> keys) {
        StorageBatch batch = storage.batch();
        Map<BlobKey, StorageBatchResult<Boolean>> results = new LinkedHashMap<>();
        for (BlobKey key : keys) {
            results.put(key, batch.delete(BlobId.of(bucket, key.value())));
        }
        batch.submit();
        List<BlobKey> deleted = new ArrayList<>();
        Map<BlobKey, CloudException> failed = new LinkedHashMap<>();
        results.forEach((key, result) -> {
            try {
                result.get(); // false means it did not exist, which counts as deleted
                deleted.add(key);
            } catch (StorageException e) {
                if (e.getCode() == 404) {
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
        com.google.cloud.storage.Blob current = storage.get(BlobId.of(bucket, key.value()));
        if (current == null) {
            throw update.ifMatch().isPresent()
                    ? new PreconditionFailedException("No blob " + bucket + "/" + key, null, provider, "notFound")
                    : new NotFoundException("No blob " + bucket + "/" + key, null, provider, "notFound");
        }
        // A metadata patch merges keys: map keys that should disappear to null to remove them.
        Map<String, String> metadata = new HashMap<>();
        if (current.getMetadata() != null) {
            current.getMetadata().keySet().forEach(k -> metadata.put(k, null));
        }
        metadata.putAll(update.metadata());
        var headers = update.headers();
        long generation = update.ifMatch().map(etag -> generation(key, etag)).orElse(current.getGeneration());
        BlobInfo patch = BlobInfo.newBuilder(BlobId.of(bucket, key.value(), generation))
                .setContentType(headers.contentType().orElse(null))
                .setContentEncoding(headers.contentEncoding().orElse(null))
                .setContentDisposition(headers.contentDisposition().orElse(null))
                .setContentLanguage(headers.contentLanguage().orElse(null))
                .setCacheControl(headers.cacheControl().orElse(null))
                .setMetadata(metadata)
                .build();
        // Generation match: fail rather than patch a blob that was replaced meanwhile.
        com.google.cloud.storage.Blob updated = storage.update(patch, BlobTargetOption.generationMatch());
        return toMetadata(key, updated);
    }

    @Override
    public void setTier(BlobKey key, StorageTier tier) {
        com.google.cloud.storage.Blob current = storage.get(BlobId.of(bucket, key.value()));
        if (current == null) {
            throw new NotFoundException("No blob " + bucket + "/" + key, null, provider, "notFound");
        }
        // Changing the storage class rewrites the object in place, keeping its metadata.
        BlobInfo target = current.toBuilder().setBlobId(BlobId.of(bucket, key.value()))
                .setStorageClass(GcsTiers.toNative(tier)).build();
        storage.copy(CopyRequest.newBuilder()
                .setSource(BlobId.of(bucket, key.value(), current.getGeneration()))
                .setTarget(target, BlobTargetOption.generationMatch(current.getGeneration()))
                .build()).getResult();
    }

    @Override
    public BlobPage list(ListOptions options) {
        List<BlobListOption> query = new ArrayList<>();
        query.add(BlobListOption.pageSize(options.pageSize()));
        options.prefix().ifPresent(p -> query.add(BlobListOption.prefix(p)));
        options.delimiter().ifPresent(d -> query.add(BlobListOption.delimiter(d)));
        options.pageToken().ifPresent(t -> query.add(BlobListOption.pageToken(t)));
        var page = storage.list(bucket, query.toArray(BlobListOption[]::new));
        List<BlobSummary> items = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        for (com.google.cloud.storage.Blob b : page.getValues()) {
            // With a delimiter, common prefixes come back as synthetic "directory" entries.
            if (b.isDirectory()) {
                prefixes.add(b.getName());
            } else {
                items.add(new BlobSummary(BlobKey.of(b.getName()), b.getSize(), etagOf(b), lastModified(b),
                        GcsTiers.fromNative(b.getStorageClass())));
            }
        }
        return new BlobPage(items, prefixes, Optional.ofNullable(page.getNextPageToken()));
    }

    @Override
    public PutResult copy(BlobKey source, BlobKey target) {
        var copied = storage.copy(CopyRequest.of(BlobId.of(bucket, source.value()), BlobId.of(bucket, target.value())))
                .getResult();
        return new PutResult(target, etagOf(copied));
    }

    @Override
    public PresignedUpload startPresignedUpload(BlobKey key, PutOptions options) {
        var request = CreateMultipartUploadRequest.builder().bucket(bucket).key(key.value());
        var headers = options.headers();
        headers.contentType().ifPresent(request::contentType);
        headers.contentEncoding().ifPresent(request::contentEncoding);
        headers.contentDisposition().ifPresent(request::contentDisposition);
        headers.contentLanguage().ifPresent(request::contentLanguage);
        headers.cacheControl().ifPresent(request::cacheControl);
        if (!options.metadata().isEmpty()) {
            request.metadata(options.metadata());
        }
        options.tier().ifPresent(tier -> request.storageClass(GcsTiers.toNative(tier)));
        return new PresignedUpload(key, multipart().createMultipartUpload(request.build()).uploadId());
    }

    /** A V4-signed XML API UploadPart URL. */
    @Override
    public PresignedUrl presignUploadPart(PresignedUpload upload, int partNumber, Duration expiry) {
        List<SignUrlOption> options = new ArrayList<>(List.of(SignUrlOption.httpMethod(HttpMethod.PUT),
                SignUrlOption.withV4Signature(),
                SignUrlOption.withQueryParams(Map.of("partNumber", String.valueOf(partNumber),
                        "uploadId", upload.uploadId()))));
        if (customEndpoint) {
            options.add(SignUrlOption.withHostName(storage.getOptions().getHost()));
        }
        Instant expiresAt = Instant.now().plus(expiry);
        var url = storage.signUrl(BlobInfo.newBuilder(bucket, upload.key().value()).build(), expiry.toSeconds(),
                TimeUnit.SECONDS, options.toArray(SignUrlOption[]::new));
        try {
            return new PresignedUrl(PresignRequest.Method.PUT, url.toURI(), expiresAt, Map.of());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("GCS returned an invalid signed URL: " + url, e);
        }
    }

    @Override
    public PutResult completePresignedUpload(PresignedUpload upload, List<UploadedPart> parts) {
        List<CompletedPart> completed = new ArrayList<>(parts.size());
        for (UploadedPart part : parts) {
            if (part.etag() == null) {
                throw new InvalidArgumentException("GCS needs the ETag of every part; part " + part.partNumber()
                        + " has none (expose the ETag header in the bucket's CORS configuration)", null, provider, null);
            }
            completed.add(CompletedPart.builder().partNumber(part.partNumber()).eTag(part.etag()).build());
        }
        multipart().completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket).key(upload.key().value()).uploadId(upload.uploadId())
                .multipartUpload(CompletedMultipartUpload.builder().parts(completed).build())
                .build());
        // Cirrus ETags on GCS are generations, which the XML API response doesn't carry.
        com.google.cloud.storage.Blob written = storage.get(BlobId.of(bucket, upload.key().value()));
        if (written == null) {
            throw new NotFoundException("Blob vanished after completing upload: " + upload.key(), null, provider, null);
        }
        return new PutResult(upload.key(), etagOf(written), checksumsOf(written));
    }

    @Override
    public void abortPresignedUpload(PresignedUpload upload) {
        try {
            multipart().abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket).key(upload.key().value()).uploadId(upload.uploadId()).build());
        } catch (RuntimeException e) {
            if (!(translate(e) instanceof NotFoundException)) { // already completed or aborted
                throw e;
            }
        }
    }

    private MultipartUploadClient multipart() {
        MultipartUploadClient client = multipart;
        if (client == null) {
            synchronized (this) {
                if (multipart == null) {
                    multipart = MultipartUploadClient.create(
                            MultipartUploadSettings.of((HttpStorageOptions) storage.getOptions()));
                }
                client = multipart;
            }
        }
        return client;
    }

    @Override
    public PresignedUrl presign(BlobKey key, PresignRequest request) {
        var method = switch (request.method()) {
            case GET -> HttpMethod.GET;
            case PUT -> HttpMethod.PUT;
        };
        BlobInfo.Builder info = BlobInfo.newBuilder(bucket, key.value());
        List<SignUrlOption> signOptions = new ArrayList<>(List.of(SignUrlOption.httpMethod(method), SignUrlOption.withV4Signature()));
        Map<String, String> headers = new LinkedHashMap<>();
        request.contentType().ifPresent(ct -> {
            // The content type becomes part of the signature, so the uploader must send it unchanged.
            info.setContentType(ct);
            signOptions.add(SignUrlOption.withContentType());
            headers.put("Content-Type", ct);
        });
        if (customEndpoint) {
            signOptions.add(SignUrlOption.withHostName(storage.getOptions().getHost()));
        }
        Instant expiresAt = Instant.now().plus(request.expiry());
        var url = storage.signUrl(info.build(), request.expiry().toSeconds(), TimeUnit.SECONDS,
                signOptions.toArray(SignUrlOption[]::new));
        try {
            return new PresignedUrl(request.method(), url.toURI(), expiresAt, headers);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("GCS returned an invalid signed URL: " + url, e);
        }
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return GcpErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(storage);
    }

    /**
     * Signed URLs need credentials that can sign (service account key, workload identity or
     * impersonation). User credentials and anonymous access cannot, so PRESIGNED_URL is only
     * advertised when the configured credentials are a {@link ServiceAccountSigner}.
     */
    private static Capabilities capabilities(Storage storage) {
        Set<StorageCapability> supported = EnumSet.allOf(StorageCapability.class);
        if (!(storage.getOptions().getCredentials() instanceof ServiceAccountSigner)) {
            supported.remove(StorageCapability.PRESIGNED_URL);
            supported.remove(StorageCapability.PRESIGNED_MULTIPART);
        }
        return Capabilities.of(supported.toArray(StorageCapability[]::new));
    }

    private long generation(BlobKey key, ETag etag) {
        try {
            return Long.parseLong(etag.value());
        } catch (NumberFormatException e) {
            throw new PreconditionFailedException("Not a GCS generation ETag for " + key + ": " + etag.value(),
                    e, provider, "conditionNotMet");
        }
    }

    private static ETag etagOf(com.google.cloud.storage.BlobInfo blob) {
        return new ETag(String.valueOf(blob.getGeneration()));
    }

    private static Instant lastModified(com.google.cloud.storage.BlobInfo blob) {
        return blob.getUpdateTimeOffsetDateTime() == null ? null : blob.getUpdateTimeOffsetDateTime().toInstant();
    }

    private static BlobMetadata toMetadata(BlobKey key, com.google.cloud.storage.BlobInfo blob) {
        return new BlobMetadata(key, blob.getSize() == null ? 0 : blob.getSize(), etagOf(blob), lastModified(blob),
                BlobHeaders.of(blob.getContentType(), blob.getContentEncoding(), blob.getContentDisposition(),
                        blob.getContentLanguage(), blob.getCacheControl()),
                blob.getMetadata(), GcsTiers.fromNative(blob.getStorageClass()), checksumsOf(blob));
    }

    private static Checksums checksumsOf(BlobInfo blob) {
        return Checksums.of(blob.getCrc32c(), blob.getMd5(), null);
    }
}
