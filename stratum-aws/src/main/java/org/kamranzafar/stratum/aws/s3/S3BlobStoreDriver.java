package org.kamranzafar.stratum.aws.s3;

import org.kamranzafar.stratum.aws.AwsErrors;
import org.kamranzafar.stratum.aws.AwsProvider;
import org.kamranzafar.stratum.capability.Capabilities;
import org.kamranzafar.stratum.error.CloudException;
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
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.awscore.presigner.PresignedRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Blob storage on Amazon S3. */
public final class S3BlobStoreDriver extends AbstractBlobStoreDriver {

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
    public PutResult put(BlobKey key, BlobContent.Sized content, PutOptions options) {
        if (content.length() > multipartThreshold) {
            return putInParts(key, content, options);
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
        options.ifMatch().ifPresent(etag -> request.ifMatch(etag.value()));
        if (options.ifNotExists()) {
            request.ifNoneMatch("*");
        }
        RequestBody body = switch (content) {
            case BlobContent.OfBytes bytes -> RequestBody.fromBytes(bytes.bytes());
            case BlobContent.OfFile file -> RequestBody.fromFile(file.path());
            case BlobContent.OfStream stream -> RequestBody.fromInputStream(stream.stream(), stream.length());
        };
        return new PutResult(key, new ETag(s3.putObject(request.build(), body).eTag()));
    }

    @Override
    public MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options) {
        return new S3MultipartUpload(s3, bucket, key, options);
    }

    private PutResult putInParts(BlobKey key, BlobContent.Sized content, PutOptions options) {
        S3MultipartUpload upload = new S3MultipartUpload(s3, bucket, key, options);
        byte[] buffer = new byte[options.partSize()];
        try (InputStream in = content.openStream()) {
            int part = 0;
            int read;
            while ((read = in.readNBytes(buffer, 0, buffer.length)) > 0) {
                upload.uploadPart(++part, buffer, read);
            }
            return upload.complete();
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
        ResponseInputStream<GetObjectResponse> in = s3.getObject(request.build());
        GetObjectResponse r = in.response();
        BlobMetadata metadata = new BlobMetadata(key, totalSize(r), new ETag(r.eTag()), r.lastModified(),
                BlobHeaders.of(r.contentType(), r.contentEncoding(), r.contentDisposition(), r.contentLanguage(),
                        r.cacheControl()),
                r.metadata());
        return new Blob(metadata, in);
    }

    @Override
    public Optional<BlobMetadata> head(BlobKey key) {
        try {
            HeadObjectResponse r = s3.headObject(b -> b.bucket(bucket).key(key.value()));
            return Optional.of(new BlobMetadata(key, r.contentLength(), new ETag(r.eTag()), r.lastModified(),
                    BlobHeaders.of(r.contentType(), r.contentEncoding(), r.contentDisposition(), r.contentLanguage(),
                            r.cacheControl()),
                    r.metadata()));
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(BlobKey key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key.value()));
    }

    @Override
    public BlobPage list(ListOptions options) {
        var request = ListObjectsV2Request.builder().bucket(bucket).maxKeys(options.pageSize());
        options.prefix().ifPresent(request::prefix);
        options.delimiter().ifPresent(request::delimiter);
        options.pageToken().ifPresent(request::continuationToken);
        ListObjectsV2Response r = s3.listObjectsV2(request.build());
        List<BlobSummary> items = r.contents().stream()
                .map(o -> new BlobSummary(BlobKey.of(o.key()), o.size(), new ETag(o.eTag()), o.lastModified()))
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
        // Signed headers other than Host (which HTTP clients set themselves) must be sent verbatim,
        // for example the Content-Type of an upload.
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!name.equalsIgnoreCase("host")) {
                headers.put(name, String.join(",", values));
            }
        });
        try {
            return new PresignedUrl(request.method(), presigned.url().toURI(), presigned.expiration(), headers);
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
