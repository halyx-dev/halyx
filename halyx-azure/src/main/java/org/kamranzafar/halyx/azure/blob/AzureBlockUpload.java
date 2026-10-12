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
package org.kamranzafar.halyx.azure.blob;

import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlockBlobCommitBlockListOptions;
import com.azure.storage.blob.options.BlockBlobStageBlockOptions;
import com.azure.storage.blob.specialized.BlockBlobClient;
import org.kamranzafar.halyx.azure.AzureProvider;
import org.kamranzafar.halyx.error.PreconditionFailedException;
import org.kamranzafar.halyx.storage.BlobKey;
import org.kamranzafar.halyx.storage.Checksums;
import org.kamranzafar.halyx.storage.ETag;
import org.kamranzafar.halyx.storage.PutOptions;
import org.kamranzafar.halyx.storage.PutResult;
import org.kamranzafar.halyx.storage.spi.ChecksumCalculator;
import org.kamranzafar.halyx.storage.spi.MultipartUploadSession;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * An upload in parts as staged blocks, committed with Put Block List. Uncommitted blocks never
 * affect the existing blob and are discarded by the service, so abort needs no request. Blocks may be
 * staged concurrently; the block list is committed in part-number order.
 */
final class AzureBlockUpload implements MultipartUploadSession {

    private final BlockBlobClient blob;
    private final BlobKey key;
    private final PutOptions options;
    private final ConcurrentSkipListMap<Integer, String> blockIds = new ConcurrentSkipListMap<>();

    AzureBlockUpload(BlockBlobClient blob, BlobKey key, PutOptions options) {
        this.blob = blob;
        this.key = key;
        this.options = options;
    }

    @Override
    public void uploadPart(int partNumber, byte[] data, int length) {
        // Block ids must be base64 and all the same length within a blob.
        String blockId = Base64.getEncoder().encodeToString(
                String.format("%010d", partNumber).getBytes(StandardCharsets.US_ASCII));
        var stage = new BlockBlobStageBlockOptions(blockId, BinaryData.fromByteBuffer(ByteBuffer.wrap(data, 0, length)));
        if (options.validateChecksum()) {
            // Azure rejects the block if it arrives corrupted.
            stage.setContentMd5(ChecksumCalculator.md5(data, 0, length));
        }
        blob.stageBlockWithResponse(stage, null, Context.NONE);
        blockIds.put(partNumber, blockId);
    }

    @Override
    public PutResult complete(Checksums expected) {
        var headers = AzureBlobStoreDriver.httpHeaders(options.headers());
        // Stored as the blob's Content-MD5 so downloads can be validated (blocks were checked on staging).
        expected.md5().ifPresent(md5 -> headers.setContentMd5(Base64.getDecoder().decode(md5)));
        var commit = new BlockBlobCommitBlockListOptions(new ArrayList<>(blockIds.values()))
                .setHeaders(headers)
                .setRequestConditions(AzureBlobStoreDriver.requestConditions(options));
        options.tier().ifPresent(tier -> commit.setTier(AzureTiers.toNative(tier)));
        if (!options.metadata().isEmpty()) {
            commit.setMetadata(options.metadata());
        }
        try {
            var response = blob.commitBlockListWithResponse(commit, null, Context.NONE);
            return new PutResult(key, new ETag(response.getValue().getETag()));
        } catch (BlobStorageException e) {
            if (options.ifNotExists() && BlobErrorCode.BLOB_ALREADY_EXISTS.equals(e.getErrorCode())) {
                throw new PreconditionFailedException("Blob already exists: " + key, e, AzureProvider.ID,
                        e.getErrorCode().toString());
            }
            throw e;
        }
    }

    @Override
    public void abort() {
        blockIds.clear();
    }
}
