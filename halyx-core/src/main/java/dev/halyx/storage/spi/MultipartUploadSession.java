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
package dev.halyx.storage.spi;

import dev.halyx.storage.Checksums;
import dev.halyx.storage.PutResult;

/**
 * A provider upload in parts: S3 multipart upload, Azure staged blocks, GCS resumable upload.
 * Core calls {@link #uploadPart} with part numbers 1, 2, 3, ..., possibly concurrently (up to
 * {@link #maxConcurrentParts()}) and out of order, then exactly one of {@link #complete} or
 * {@link #abort()} after every part has returned. Every part except the last is exactly the configured
 * part size. Implementations must not keep {@code data} after {@code uploadPart} returns.
 */
public interface MultipartUploadSession {

    void uploadPart(int partNumber, byte[] data, int length);

    /** How many parts may upload at once; 1 for inherently sequential uploads (a resumable stream). */
    default int maxConcurrentParts() {
        return Integer.MAX_VALUE;
    }

    /**
     * Makes the blob visible, applying the write conditions of the upload.
     *
     * @param expected checksums of the whole content when validation was requested, otherwise none
     */
    PutResult complete(Checksums expected);

    /** Discards uploaded parts. Must not throw. */
    void abort();
}
