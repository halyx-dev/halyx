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

import java.util.Objects;

/**
 * A multipart upload whose parts a client sends straight to storage with presigned URLs; see
 * {@link BlobStore#startPresignedUpload}. Keep it on the server (or treat it as untrusted input when a
 * client hands it back): {@code uploadId} is opaque and may encode the headers and metadata the blob
 * gets on completion.
 *
 * @param key      the blob being uploaded
 * @param uploadId the provider's upload id
 */
public record PresignedUpload(BlobKey key, String uploadId) {

    public PresignedUpload {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(uploadId, "uploadId");
        if (uploadId.isBlank()) {
            throw new IllegalArgumentException("uploadId must not be blank");
        }
    }
}
