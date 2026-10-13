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

/**
 * A part the client uploaded with a {@linkplain BlobStore#presignUploadPart presigned part URL}.
 *
 * @param partNumber the part's number, from 1
 * @param etag       the {@code ETag} response header of the part upload. S3 and GCS need it to complete
 *                   the upload (the bucket's CORS rules must expose {@code ETag} to browsers); Azure
 *                   ignores it, so it may be null there
 */
public record UploadedPart(int partNumber, String etag) {

    public UploadedPart {
        if (partNumber < 1 || partNumber > PresignedUploads.MAX_PARTS) {
            throw new IllegalArgumentException("partNumber must be between 1 and " + PresignedUploads.MAX_PARTS);
        }
        if (etag != null && etag.isBlank()) {
            etag = null;
        }
    }
}
