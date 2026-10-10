package org.kamranzafar.cirrus.aws.s3;

import org.kamranzafar.cirrus.aws.AwsErrors;
import org.kamranzafar.cirrus.aws.AwsProvider;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
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
import org.kamranzafar.cirrus.storage.PresignedUpload;
import org.kamranzafar.cirrus.storage.PresignedUrl;
import org.kamranzafar.cirrus.storage.PutOptions;
import org.kamranzafar.cirrus.storage.PutResult;
import org.kamranzafar.cirrus.storage.StorageCapability;
import org.kamranzafar.cirrus.storage.StorageTier;
import org.kamranzafar.cirrus.storage.UploadedPart;
import org.kamranzafar.cirrus.storage.spi.AbstractBlobStoreDriver;
import org.kamranzafar.cirrus.storage.spi.BufferPool;
import org.kamranzafar.cirrus.storage.spi.MultipartUploadSession;
import org.kamranzafar.cirrus.storage.spi.PartUploader;
import software.amazon.awssdk.awscore.presigner.PresignedRequest;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Error;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Blob storage on Amazon S3. */
public final class S3BlobStoreDriver extends AbstractBlobStoreDriver {

    /** DeleteObjects accepts up to 1,000 keys per request. */
    private static final int MAX_BATCH = 1000;

    private final S3Client s3;
    private final S3Presigner presigner;
    private final long multipartThreshold;

    /**
     * @param multipartThreshold content longer than this is uploaded with a multipart upload (a single
     *                           S3 PUT is limited to 5 GiB)
     */
    public S3BlobStoreDriver(String bucket, S3Client s3, S3Presigner presigner, long multipartThreshold) {
        super(AwsProvider.ID, bucket, Capabilities.of(StorageCapability.values()));
        this.s3 = s3;
        this.presigner = presigner;
        this.multipartThreshold = multipartThreshold;
    }

    @Override
    public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options, Checksums expected) {
        if (content.length() > multipartThreshold) {
            return putInParts(key, content, options, expected);
        }
        var request = PutObjectRequest.builder().bucket(bucket).key(key.value()).contentLength(content.length());
        var headers = options.headers();
        headers.contentType().ifPresent(request::contentType);
        headers.contentEncoding().ifPresent(request::contentEncoding);
        headers.contentDisposition().ifPresent(request::contentDisposition);
        headers.contentLanguage().ifPresent(request::contentLanguage);
        headers.cacheControl().ifPresent(request::cacheControl);
        if (!options.metadata().isEmpty()) {
            request.metadata(options.metadata());
        }
        options.tier().ifPresent(tier -> request.storageClass(S3Tiers.toNative(tier)));
        options.ifMatch().ifPresent(etag -> request.ifMatch(etag.value()));
        if (options.ifNotExists()) {
            request.ifNoneMatch("*");
        }
        // S3 rejects the upload if the content does not match, and stores the checksum.
        expected.crc32c().ifPresent(request::checksumCRC32C);
        RequestBody body = switch (content) {
            case BlobContent.OfBytes bytes -> RequestBody.fromBytes(bytes.bytes());
            case BlobContent.OfFile file -> RequestBody.fromFile(file.path());
            case BlobContent.OfStream stream -> RequestBody.fromInputStream(stream.stream(), stream.length());
        };
        var response = s3.putObject(request.build(), body);
        return new PutResult(key, new ETag(response.eTag()),
                checksums(response.checksumCRC32C(), response.checksumCRC32()));
    }

    @Override
    public MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options) {
        return new S3MultipartUpload(s3, bucket, key, options);
    }

    private PutResult putInParts(BlobKey key, BlobContent.Sized content, PutOptions options, Checksums expected) {
        BufferPool pool = BufferPool.shared();
        var upload = new PartUploader(new S3MultipartUpload(s3, bucket, key, options), options.uploadConcurrency(),
                pool, Function.identity());
        try (InputStream in = content.openStream()) {
            int part = 0;
            while (true) {
                byte[] buffer = pool.acquire(options.partSize());
                int read = in.readNBytes(buffer, 0, buffer.length);
                if (read == 0 && part > 0) {
                    pool.release(buffer);
                    break;
                }
                upload.submit(++part, buffer, read);
                if (read < buffer.length) {
                    break;
                }
            }
            return upload.complete(expected);
        } catch (IOException e) {
            upload.abort();
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            upload.abort();
            throw e;
        }
    }

    @Override
    public Blob get(BlobKey key, GetOptions options) {
        var request = GetObjectRequest.builder().bucket(bucket).key(key.value());
        options.range().ifPresent(range -> request.range(range.toHttpHeader()));
        options.ifMatch().ifPresent(etag -> request.ifMatch(etag.value()));
        if (options.range().isEmpty()) {
            request.checksumMode(ChecksumMode.ENABLED);
        }
        ResponseInputStream<GetObjectResponse> in = s3.getObject(request.build());
        GetObjectResponse r = in.response();
        BlobMetadata metadata = new BlobMetadata(key, totalSize(r), new ETag(r.eTag()), r.lastModified(),
                BlobHeaders.of(r.contentType(), r.contentEncoding(), r.contentDisposition(), r.contentLanguage(),
                        r.cacheControl()),
                r.metadata(), S3Tiers.fromNative(r.storageClassAsString()),
                checksums(r.checksumCRC32C(), r.checksumCRC32()));
        return new Blob(metadata, in);
    }

    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        try {
            HeadObjectResponse r = s3.headObject(b -> b.bucket(bucket).key(key.value()).checksumMode(ChecksumMode.ENABLED));
            return Optional.of(toMetadata(key, r));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(BlobKey key, DeleteOptions options) {
        var request = DeleteObjectRequest.builder().bucket(bucket).key(key.value());
        options.ifMatchETag().ifPresent(etag -> request.ifMatch(etag.value()));
        try {
            s3.deleteObject(request.build());
        } catch (S3Exception e) {
            if (options.isConditional() && e.statusCode() == 404) {
                throw new PreconditionFailedException("No blob " + bucket + "/" + key + " to delete conditionally",
                        e, provider, "NoSuchKey");
            }
            throw e;
        }
    }

    @Override
    public int maxBatchSize() {
        return MAX_BATCH;
    }

    @Override
    public BatchDeleteResult deleteBatch(List<BlobKey> keys) {
        List<ObjectIdentifier> objects = keys.stream().map(k -> ObjectIdentifier.builder().key(k.value()).build()).toList();
        var response = s3.deleteObjects(b -> b.bucket(bucket).delete(d -> d.objects(objects).quiet(true)));
        Map<BlobKey, CloudException> failed = new LinkedHashMap<>();
        for (S3Error error : response.errors()) {
            if (!"NoSuchKey".equals(error.code())) {
                failed.put(BlobKey.of(error.key()), AwsErrors.fromCode(error.code(), error.message()));
            }
        }
        List<BlobKey> deleted = new ArrayList<>(keys);
        deleted.removeAll(failed.keySet());
        return new BatchDeleteResult(deleted, failed);
    }

    @Override
    public BlobMetadata updateMetadata(BlobKey key, MetadataUpdate update) {
        // S3 cannot edit metadata in place: copy the object onto itself, replacing its metadata and
        // keeping its storage class (which a copy would otherwise reset to STANDARD).
        var request = CopyObjectRequest.builder()
                .sourceBucket(bucket).sourceKey(key.value())
                .destinationBucket(bucket).destinationKey(key.value())
                .metadataDirective(MetadataDirective.REPLACE)
                .metadata(update.metadata());
        String storageClass = update.basis().flatMap(S3BlobStoreDriver::exactStorageClass)
                .orElseGet(() -> currentOrThrow(key, update.ifMatch().isPresent()).storageClassAsString());
        if (storageClass != null) {
            request.storageClass(storageClass);
        }
        var headers = update.headers();
        headers.contentType().ifPresent(request::contentType);
        headers.contentEncoding().ifPresent(request::contentEncoding);
        headers.contentDisposition().ifPresent(request::contentDisposition);
        headers.contentLanguage().ifPresent(request::contentLanguage);
        headers.cacheControl().ifPresent(request::cacheControl);
        update.ifMatch().ifPresent(etag -> request.copySourceIfMatch(etag.value()));
        try {
            s3.copyObject(request.build());
        } catch (S3Exception e) {
            if (e.statusCode() == 404 && update.ifMatch().isPresent()) {
                throw new PreconditionFailedException("No blob " + bucket + "/" + key, e, provider, "NoSuchKey");
            }
            throw e;
        }
        return head(key).orElseThrow(() -> new NotFoundException("Blob vanished during update: " + key, null, provider, null));
    }

    @Override
    public void setTier(BlobKey key, StorageTier tier) {
        // One request: S3 rejects a copy onto itself that changes nothing, which means the tier is already set.
        try {
            s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(key.value())
                    .destinationBucket(bucket).destinationKey(key.value())
                    .metadataDirective(MetadataDirective.COPY)
                    .storageClass(S3Tiers.toNative(tier)));
        } catch (S3Exception e) {
            if (isCopyOntoItself(e)) {
                return;
            }
            throw e;
        }
    }

    /** The native storage class for tiers that map to exactly one class; others need a lookup. */
    private static Optional<String> exactStorageClass(BlobMetadata basis) {
        return basis.tier().filter(t -> t == StorageTier.HOT || t == StorageTier.COLD).map(S3Tiers::toNative);
    }

    /** "This copy request is illegal because it is trying to copy an object to itself without changing ..." */
    private static boolean isCopyOntoItself(S3Exception e) {
        var details = e.awsErrorDetails();
        return e.statusCode() == 400 && details != null && "InvalidRequest".equals(details.errorCode())
                && details.errorMessage() != null && details.errorMessage().contains("to itself");
    }

    private HeadObjectResponse currentOrThrow(BlobKey key, boolean conditional) {
        try {
            return s3.headObject(b -> b.bucket(bucket).key(key.value()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw conditional
                        ? new PreconditionFailedException("No blob " + bucket + "/" + key, e, provider, "NoSuchKey")
                        : new NotFoundException("No blob " + bucket + "/" + key, e, provider, "NoSuchKey");
            }
            throw e;
        }
    }

    @Override
    public BlobPage list(ListOptions options) {
        var request = ListObjectsV2Request.builder().bucket(bucket).maxKeys(options.pageSize());
        options.prefix().ifPresent(request::prefix);
        options.delimiter().ifPresent(request::delimiter);
        options.pageToken().ifPresent(request::continuationToken);
        ListObjectsV2Response r = s3.listObjectsV2(request.build());
        List<BlobSummary> items = r.contents().stream()
                .map(o -> new BlobSummary(BlobKey.of(o.key()), o.size(), new ETag(o.eTag()), o.lastModified(),
                        S3Tiers.fromNative(o.storageClassAsString())))
                .toList();
        boolean truncated = Boolean.TRUE.equals(r.isTruncated());
        List<String> prefixes = r.commonPrefixes().stream().map(CommonPrefix::prefix).toList();
        return new BlobPage(items, prefixes, truncated ? Optional.ofNullable(r.nextContinuationToken()) : Optional.empty());
    }

    @Override
    public PutResult copy(BlobKey source, BlobKey target) {
        var r = s3.copyObject(b -> b.sourceBucket(bucket).sourceKey(source.value())
                .destinationBucket(bucket).destinationKey(target.value()));
        return new PutResult(target, new ETag(r.copyObjectResult().eTag()));
    }

    @Override
    public PresignedUpload startPresignedUpload(BlobKey key, PutOptions options) {
        String uploadId = s3.createMultipartUpload(S3MultipartUpload.createRequest(bucket, key, options).build()).uploadId();
        return new PresignedUpload(key, uploadId);
    }

    @Override
    public PresignedUrl presignUploadPart(PresignedUpload upload, int partNumber, Duration expiry) {
        PresignedRequest presigned = presigner.presignUploadPart(p -> p.signatureDuration(expiry)
                .uploadPartRequest(u -> u.bucket(bucket).key(upload.key().value())
                        .uploadId(upload.uploadId()).partNumber(partNumber)));
        return toPresignedUrl(PresignRequest.Method.PUT, presigned);
    }

    @Override
    public PutResult completePresignedUpload(PresignedUpload upload, List<UploadedPart> parts) {
        List<CompletedPart> completed = new ArrayList<>(parts.size());
        for (UploadedPart part : parts) {
            if (part.etag() == null) {
                throw new InvalidArgumentException("S3 needs the ETag of every part; part " + part.partNumber()
                        + " has none (expose the ETag header in the bucket's CORS rules)", null, provider, null);
            }
            completed.add(CompletedPart.builder().partNumber(part.partNumber()).eTag(part.etag()).build());
        }
        var response = s3.completeMultipartUpload(b -> b.bucket(bucket).key(upload.key().value())
                .uploadId(upload.uploadId()).multipartUpload(m -> m.parts(completed)));
        return new PutResult(upload.key(), new ETag(response.eTag()),
                checksums(response.checksumCRC32C(), response.checksumCRC32()));
    }

    @Override
    public void abortPresignedUpload(PresignedUpload upload) {
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(upload.key().value()).uploadId(upload.uploadId()));
        } catch (S3Exception e) {
            if (e.statusCode() != 404) { // NoSuchUpload: already completed or aborted
                throw e;
            }
        }
    }

    @Override
    public PresignedUrl presign(BlobKey key, PresignRequest request) {
        PresignedRequest presigned = switch (request.method()) {
            case GET -> presigner.presignGetObject(p -> p.signatureDuration(request.expiry())
                    .getObjectRequest(g -> g.bucket(bucket).key(key.value())));
            case PUT -> presigner.presignPutObject(p -> p.signatureDuration(request.expiry())
                    .putObjectRequest(put -> {
                        put.bucket(bucket).key(key.value());
                        request.contentType().ifPresent(put::contentType);
                    }));
        };
        return toPresignedUrl(request.method(), presigned);
    }

    private static PresignedUrl toPresignedUrl(PresignRequest.Method method, PresignedRequest presigned) {
        // Signed headers other than Host (which HTTP clients set themselves) must be sent verbatim,
        // for example the Content-Type of an upload.
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                headers.put(name, String.join(",", values));
            }
        });
        try {
            return new PresignedUrl(method, presigned.url().toURI(), presigned.expiration(), headers);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("S3 presigner returned an invalid URL: " + presigned.url(), e);
        }
    }

    @Override
    protected Optional<CloudException> translateNative(Throwable error) {
        return AwsErrors.translate(error);
    }

    @Override
    protected List<Object> nativeClients() {
        return List.of(s3, presigner);
    }

    private BlobMetadata toMetadata(BlobKey key, HeadObjectResponse r) {
        return new BlobMetadata(key, r.contentLength(), new ETag(r.eTag()), r.lastModified(),
                BlobHeaders.of(r.contentType(), r.contentEncoding(), r.contentDisposition(), r.contentLanguage(),
                        r.cacheControl()),
                r.metadata(), S3Tiers.fromNative(r.storageClassAsString()),
                checksums(r.checksumCRC32C(), r.checksumCRC32()));
    }

    /**
     * Full-object checksums only. Composite multipart checksums ("checksum-of-checksums", suffixed
     * "-N") do not describe the content as a whole and are dropped.
     */
    static Checksums checksums(String crc32c, String crc32) {
        return Checksums.of(fullObject(crc32c), null, fullObject(crc32));
    }

    private static String fullObject(String value) {
        return value == null || value.contains("-") ? null : value;
    }

    /** Full object size; for range reads S3 reports it after the slash in {@code Content-Range}. */
    private static long totalSize(GetObjectResponse r) {
        String contentRange = r.contentRange();
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0 && !contentRange.endsWith("*")) {
                return Long.parseLong(contentRange.substring(slash + 1).trim());
            }
        }
        return r.contentLength();
    }
}
