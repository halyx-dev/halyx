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
package org.kamranzafar.cirrus.aws.s3;

import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.Checksums;
import org.kamranzafar.cirrus.storage.ETag;
import org.kamranzafar.cirrus.storage.PutOptions;
import org.kamranzafar.cirrus.storage.PutResult;
import org.kamranzafar.cirrus.storage.spi.ChecksumCalculator;
import org.kamranzafar.cirrus.storage.spi.MultipartUploadSession;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumType;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * An S3 multipart upload. Parts must be at least 5 MiB except the last, which core guarantees. Parts may
 * upload concurrently; they are committed in part-number order.
 */
final class S3MultipartUpload implements MultipartUploadSession {

    private final S3Client s3;
    private final String bucket;
    private final BlobKey key;
    private final PutOptions options;
    private final String uploadId;
    private final ConcurrentSkipListMap<Integer, CompletedPart> parts = new ConcurrentSkipListMap<>();

    S3MultipartUpload(S3Client s3, String bucket, BlobKey key, PutOptions options) {
        this.s3 = s3;
        this.bucket = bucket;
        this.key = key;
        this.options = options;
        var request = createRequest(bucket, key, options);
        if (options.validateChecksum()) {
            // A full-object CRC32C (not a checksum of part checksums), so it can be validated end to end.
            request.checksumAlgorithm(ChecksumAlgorithm.CRC32_C).checksumType(ChecksumType.FULL_OBJECT);
        }
        this.uploadId = s3.createMultipartUpload(request.build()).uploadId();
    }

    /** CreateMultipartUpload carrying the blob's headers, metadata and storage class. */
    static CreateMultipartUploadRequest.Builder createRequest(String bucket, BlobKey key, PutOptions options) {
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
        options.tier().ifPresent(tier -> request.storageClass(S3Tiers.toNative(tier)));
        return request;
    }

    @Override
    public void uploadPart(int partNumber, byte[] data, int length) {
        String partCrc = options.validateChecksum() ? ChecksumCalculator.crc32c(data, 0, length) : null;
        var response = s3.uploadPart(b -> b.bucket(bucket).key(key.value()).uploadId(uploadId)
                        .partNumber(partNumber).contentLength((long) length).checksumCRC32C(partCrc),
                RequestBody.fromByteBuffer(ByteBuffer.wrap(data, 0, length)));
        parts.put(partNumber, CompletedPart.builder().partNumber(partNumber).eTag(response.eTag())
                .checksumCRC32C(response.checksumCRC32C()).build());
    }

    @Override
    public PutResult complete(Checksums expected) {
        var response = s3.completeMultipartUpload(b -> {
            b.bucket(bucket).key(key.value()).uploadId(uploadId).multipartUpload(m -> m.parts(parts.values()));
            expected.crc32c().ifPresent(crc -> b.checksumCRC32C(crc).checksumType(ChecksumType.FULL_OBJECT));
            options.ifMatch().ifPresent(etag -> b.ifMatch(etag.value()));
            if (options.ifNotExists()) {
                b.ifNoneMatch("*");
            }
        });
        return new PutResult(key, new ETag(response.eTag()),
                S3BlobStoreDriver.checksums(response.checksumCRC32C(), response.checksumCRC32()));
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
