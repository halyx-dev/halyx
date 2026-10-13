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

/** Limits shared by every provider's presigned multipart upload. */
public final class PresignedUploads {

    /** S3 and GCS accept up to 10,000 parts (Azure 50,000); the portable limit is the lower one. */
    public static final int MAX_PARTS = 10_000;

    /** Every part except the last must be at least this big on S3 and GCS. */
    public static final int MIN_PART_SIZE = 5 * 1024 * 1024;

    private PresignedUploads() {
    }
}
