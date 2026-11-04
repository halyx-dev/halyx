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
package dev.halyx.error;

/**
 * Data failed an integrity check: a checksum over the bytes sent or received doesn't match the provider's, or sealed
 * data failed authentication when opened (wrong AAD, changed or truncated data, or data sealed under another data
 * key). Not retryable: a checksum mismatch in transit may pass on a fresh request, which the caller can make, but
 * repeating the same decryption fails the same way.
 */
public final class DataIntegrityException extends CloudException {

    public DataIntegrityException(String message) {
        this(message, null, null, null);
    }

    public DataIntegrityException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause, provider, providerErrorCode);
    }
}
