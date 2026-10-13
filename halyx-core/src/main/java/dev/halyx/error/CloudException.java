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

import java.util.Optional;

/**
 * Root of the portable error model. Every provider translates its native SDK exceptions into one of
 * the permitted subtypes, so application code can handle failures the same way on any cloud:
 *
 * <pre>{@code
 * try {
 *     store.get(key);
 * } catch (NotFoundException e) {
 *     // same branch on S3, Azure Blob and GCS
 * }
 * }</pre>
 *
 * The hierarchy is sealed: providers cannot invent new types, so callers can rely on it being
 * complete. The native exception is always available as {@link #getCause()}.
 */
public abstract sealed class CloudException extends RuntimeException
        permits NotFoundException, AlreadyExistsException, PreconditionFailedException,
                InvalidArgumentException, AccessDeniedException, ThrottledException,
                UnavailableException, UnsupportedFeatureException, DataIntegrityException,
                UnknownCloudException {

    private final String provider;
    private final String providerErrorCode;

    protected CloudException(String message, Throwable cause, String provider, String providerErrorCode) {
        super(message, cause);
        this.provider = provider;
        this.providerErrorCode = providerErrorCode;
    }

    /** Whether retrying the same request may succeed (throttling, transient unavailability). */
    public boolean isRetryable() {
        return false;
    }

    /** The provider id ({@code "aws"}, {@code "azure"}, ...) that raised the error, when known. */
    public Optional<String> provider() {
        return Optional.ofNullable(provider);
    }

    /** The provider's own error code, such as {@code NoSuchKey} or {@code BlobNotFound}. */
    public Optional<String> providerErrorCode() {
        return Optional.ofNullable(providerErrorCode);
    }
}
