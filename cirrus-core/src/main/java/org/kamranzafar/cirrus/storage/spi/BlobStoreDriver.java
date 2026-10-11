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
package org.kamranzafar.cirrus.storage.spi;

import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;
import org.kamranzafar.cirrus.storage.BatchDeleteResult;
import org.kamranzafar.cirrus.storage.ChecksumAlgorithm;
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
import org.kamranzafar.cirrus.storage.ByteRange;
import org.kamranzafar.cirrus.storage.ETag;
import org.kamranzafar.cirrus.storage.GetOptions;
import org.kamranzafar.cirrus.storage.ListOptions;
import org.kamranzafar.cirrus.storage.PresignRequest;
import org.kamranzafar.cirrus.storage.PresignedUpload;
import org.kamranzafar.cirrus.storage.PresignedUrl;
import org.kamranzafar.cirrus.storage.PutOptions;
import org.kamranzafar.cirrus.storage.PutResult;
import org.kamranzafar.cirrus.storage.UploadedPart;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * The checksums core computes for validated uploads: the ones this provider checks on arrival and
     * stores. Defaults to CRC32C, which the JDK computes with hardware instructions; a provider that
     * only understands MD5 (Azure) overrides this.
     */
    default Set<ChecksumAlgorithm> uploadChecksums() {
        return Set.of(ChecksumAlgorithm.CRC32C);
    }

    /** Starts an upload in parts, used by core for streamed and unknown-length writes. */
    MultipartUploadSession startMultipartUpload(BlobKey key, PutOptions options);

    Blob get(BlobKey key, GetOptions options);

    /**
     * The bytes {@code [offset, offset + length)} of version {@code version}, for one part of a parallel
     * read. Core already holds the metadata, so drivers whose {@link #get} looks metadata up separately
     * override this to skip that lookup.
     */
    default InputStream readRange(BlobKey key, ETag version, long offset, long length) {
        return get(key, GetOptions.builder().range(ByteRange.of(offset, length)).ifMatch(version).build()).content();
    }

    Optional<BlobMetadata> head(BlobKey key);

    void delete(BlobKey key, DeleteOptions options);

    /** Largest batch {@link #deleteBatch} accepts; only used with {@link StorageCapability#BATCH_DELETE}. */
    default int maxBatchSize() {
        return 1;
    }

    /** See {@link org.kamranzafar.cirrus.storage.BlobStore#startPresignedUpload}; core has validated the options. */
    default PresignedUpload startPresignedUpload(BlobKey key, PutOptions options) {
        throw new UnsupportedFeatureException("Presigned multipart upload is not supported");
    }

    default PresignedUrl presignUploadPart(PresignedUpload upload, int partNumber, Duration expiry) {
        throw new UnsupportedFeatureException("Presigned multipart upload is not supported");
    }

    /** {@code parts} is non-empty, sorted by part number, without duplicates. */
    default PutResult completePresignedUpload(PresignedUpload upload, List<UploadedPart> parts) {
        throw new UnsupportedFeatureException("Presigned multipart upload is not supported");
    }

    default void abortPresignedUpload(PresignedUpload upload) {
        throw new UnsupportedFeatureException("Presigned multipart upload is not supported");
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
