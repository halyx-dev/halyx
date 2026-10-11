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
package org.kamranzafar.cirrus.storage;

/** Checksum algorithms providers store with blobs. Values are base64-encoded big-endian digests. */
public enum ChecksumAlgorithm {
    /** Stored by S3 (when requested) and GCS (always). */
    CRC32C,
    /** Stored by Azure (Content-MD5) and GCS (non-composite objects). */
    MD5,
    /** The S3 SDK's default upload checksum. */
    CRC32
}
