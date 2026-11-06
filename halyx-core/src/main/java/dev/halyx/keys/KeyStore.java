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
package dev.halyx.keys;

import dev.halyx.capability.Capabilities;
import dev.halyx.interceptor.InterceptorChain;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * A location of named keys: AWS KMS in the provider's account and region, an Azure Key Vault, a Google Cloud KMS key
 * ring, or a Vault Transit mount. Hands out {@link CryptoKey}s by name, and opens data sealed under any of its keys,
 * finding the key from the sealed data itself. Halyx never creates, rotates or deletes keys. Thread-safe.
 */
public interface KeyStore {

    String provider();

    /** The store's location, as configured; empty for the provider's default (AWS, or Vault's {@code transit}). */
    Optional<String> location();

    /** The key {@code name} (the store's prefix is added). Makes no key service call. */
    CryptoKey key(String name);

    /** The key {@code name}, used as {@code options} say. Asking again with equal options returns the same key. */
    CryptoKey key(String name, KeyOptions options);

    /** The metadata of every key whose name starts with {@code prefix} (empty for all), names without the store's prefix. */
    List<KeyMetadata> keys(String prefix);

    /**
     * Opens data sealed under any key of this store, using the first key entry in the store's location. Throws
     * {@code InvalidArgumentException} if no entry is, and {@code DataIntegrityException} for wrong {@code aad} or
     * changed data. {@code aad} may be empty.
     */
    byte[] decrypt(SealedData sealed, byte[] aad);

    /** Unwraps a data key that a key of this store wrapped. */
    DataKey decryptDataKey(WrappedKey wrapped);

    /**
     * The plaintext of sealed data read from {@code sealed}, opened one segment at a time: for data too large to hold
     * in memory. Reading fails with {@code DataIntegrityException} at the first changed segment, or at the end if the
     * data was truncated. Closing it closes {@code sealed}.
     */
    InputStream decryptingStream(InputStream sealed, byte[] aad);

    /**
     * A plaintext range of sealed data stored elsewhere, such as a blob, without reading the rest: {@code header} was
     * read with {@link SealedHeader#read}, the sealed data is {@code sealedLength} bytes long, and {@code segments}
     * starts at {@code header.segmentStart(plaintextOffset)} and reaches at least
     * {@code header.segmentEnd(plaintextOffset + plaintextLength, sealedLength)}. Each segment read is authenticated,
     * and the final segment is known from {@code sealedLength}, so changed, truncated or extended data fails with
     * {@code DataIntegrityException}. A range outside the plaintext is {@code InvalidArgumentException}. Closing the
     * stream closes {@code segments}.
     */
    InputStream decryptingStream(SealedHeader header, InputStream segments, long sealedLength, long plaintextOffset,
                                 long plaintextLength, byte[] aad);

    /** Checks that the key service answers an authenticated read, past every cache, for health checks. */
    void ping();

    /** Key service calls and cache hits of this store in this JVM. */
    KeyStoreStatistics statistics();

    /** Store-level features: {@link KeyCapability#MAC}, {@link KeyCapability#SERVER_SIDE_REWRAP}. */
    Capabilities capabilities();

    /**
     * The asynchronous view, whose calls return {@code CompletableFuture}s; non-blocking where the provider's driver
     * has a non-blocking path. Cheap to call.
     */
    default AsyncKeyStore async() {
        return async(InterceptorChain.blocking());
    }

    /** The asynchronous view, running any blocking work on {@code blocking}, such as an application's scheduler. */
    default AsyncKeyStore async(Executor blocking) {
        return AsyncKeyStore.onExecutor(this, blocking);
    }

    /** The native client, such as {@code KmsClient}, {@code KeyClient} or {@code KeyManagementServiceClient}. */
    <T> T unwrap(Class<T> nativeType);
}
