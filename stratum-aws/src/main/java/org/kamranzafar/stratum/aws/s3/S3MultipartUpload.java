package org.kamranzafar.stratum.aws.s3;

import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.ETag;
import org.kamranzafar.stratum.storage.PutOptions;
import org.kamranzafar.stratum.storage.PutResult;
import org.kamranzafar.stratum.storage.spi.MultipartUploadSession;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** An S3 multipart upload. Parts must be at least 5 MiB except the last, which core guarantees. */
final class S3MultipartUpload implements MultipartUploadSession {

    private final S3Client s3;
    private final String bucket;
    private final BlobKey key;
    private final PutOptions options;
    private final String uploadId;
    private final List<CompletedPart> parts = new ArrayList<>();

    S3MultipartUpload(S3Client s3, String bucket, BlobKey key, PutOptions options) {
        this.s3 = s3;
        this.bucket = bucket;
        this.key = key;
        this.options = options;
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
        this.uploadId = s3.createMultipartUpload(request.build()).uploadId();
    }

    @Override
    public void uploadPart(int partNumber, byte[] data, int length) {
        var response = s3.uploadPart(b -> b.bucket(bucket).key(key.value()).uploadId(uploadId)
                        .partNumber(partNumber).contentLength((long) length),
                RequestBody.fromByteBuffer(ByteBuffer.wrap(data, 0, length)));
        parts.add(CompletedPart.builder().partNumber(partNumber).eTag(response.eTag()).build());
    }

    @Override
    public PutResult complete() {
        var response = s3.completeMultipartUpload(b -> {
            b.bucket(bucket).key(key.value()).uploadId(uploadId).multipartUpload(m -> m.parts(parts));
            options.ifMatch().ifPresent(etag -> b.ifMatch(etag.value()));
            if (options.ifNotExists()) {
                b.ifNoneMatch("*");
            }
        });
        return new PutResult(key, new ETag(response.eTag()));
    }

    @Override
    public void abort() {
        try {
            s3.abortMultipartUpload(b -> b.bucket(bucket).key(key.value()).uploadId(uploadId));
        } catch (RuntimeException ignored) {
            // Best effort: a bucket lifecycle rule should clean up abandoned uploads.
        }
    }
}
