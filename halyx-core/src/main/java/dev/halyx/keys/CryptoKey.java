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

import java.io.OutputStream;
import java.security.PublicKey;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * One key held by a key service, the same on every cloud. The key service only ever wraps data keys and signs
 * digests: data is encrypted locally with AES-256-GCM in the HLXS format, and signatures are verified locally with the
 * public key, so there are no size limits and verification needs no call.
 *
 * <pre>{@code
 * CryptoKey orders = halyx.provider("cloud").keyStore().key("orders-data");
 * SealedData sealed = orders.encrypt(json, orderId.getBytes(UTF_8));   // one wrap call
 * byte[] plain = orders.decrypt(sealed, orderId.getBytes(UTF_8));      // one unwrap call
 * }</pre>
 *
 * The first use reads the key's metadata and checks it against the options: a key whose type doesn't fit the purpose
 * or algorithm throws {@code InvalidArgumentException}. Plaintext, data keys and AAD never appear in exceptions, logs
 * or {@code toString}. Thread-safe.
 */
public interface CryptoKey {

    /** The store this key belongs to. */
    KeyStore store();

    /** The key's name in its store, without the store's prefix. */
    String name();

    String provider();

    /** The options the key was opened with ({@link KeyStore#key(String, KeyOptions)}); defaults when none. */
    KeyOptions options();

    /** The key's metadata, read on first use and refreshed every few minutes. */
    KeyMetadata metadata();

    /** Seals {@code plaintext}, bound to {@code aad} (may be empty), with a fresh data key unless reuse is on. */
    SealedData encrypt(byte[] plaintext, byte[] aad);

    /**
     * Opens data sealed under this key. Throws {@code InvalidArgumentException} if it wasn't, and
     * {@code DataIntegrityException} for wrong {@code aad} or changed data.
     */
    byte[] decrypt(SealedData sealed, byte[] aad);

    /**
     * A stream that seals what is written to it into {@code sink}, one segment at a time, for data too large to hold in
     * memory. Close it to write the final segment; data from an unclosed stream fails to open as truncated.
     */
    OutputStream encryptingStream(OutputStream sink, byte[] aad);

    /** A fresh data key and its wrapped form, for applications with their own format. */
    DataKey generateDataKey();

    /** Unwraps a data key this key wrapped. */
    DataKey decryptDataKey(WrappedKey wrapped);

    /** Wraps an existing data key with this key, such as one from another key's {@link #generateDataKey()}. */
    WrappedKey wrap(DataKey dataKey);

    /**
     * Rewraps sealed data from any key of this key's store onto this key's current version: after a rotation, or to
     * move data between keys. Only the header changes, never the encrypted body.
     */
    SealedData rewrap(SealedData sealed);

    /** Rewraps sealed data from a key of {@code from}, which may be on another provider or cloud, onto this key. */
    SealedData rewrap(SealedData sealed, KeyStore from);

    /**
     * Adds this key to sealed data that {@code from} can open, so either can open it: for data that must be readable
     * on several clouds. Only the header changes. At most 16 keys.
     */
    SealedData addTo(SealedData sealed, KeyStore from);

    /** Hashes {@code data} locally and has the key service sign the digest. */
    Signature sign(byte[] data);

    /** Signs a digest already computed with the signing algorithm's hash. */
    Signature signDigest(byte[] digest);

    /**
     * Verifies {@code signature} over {@code data} locally, with the public key of the version that signed: no key
     * service call once that public key is cached. False for any bad or foreign signature.
     */
    boolean verify(byte[] data, Signature signature);

    /** HMAC-SHA256 of {@code data} with the key (requires {@link KeyCapability#MAC}). */
    byte[] mac(byte[] data);

    /** Whether {@code mac} is the key's HMAC-SHA256 of {@code data} (requires {@link KeyCapability#MAC}). */
    boolean verifyMac(byte[] data, byte[] mac);

    /** The public key of a signing key's {@code version}. */
    PublicKey publicKey(String version);

    /** The public key of the current version. */
    PublicKey currentPublicKey();

    /**
     * The public key of every enabled version, by version: for JWK sets. A provider without visible versions (AWS)
     * gives one entry, under the empty string.
     */
    Map<String, PublicKey> publicKeys();

    /** What this key can do: {@link KeyCapability#SIGN}, {@link KeyCapability#MAC}, and the store's features. */
    Capabilities capabilities();

    /** The asynchronous view; non-blocking where the provider's driver is. Cheap to call. */
    default AsyncCryptoKey async() {
        return async(InterceptorChain.blocking());
    }

    /** The asynchronous view, running any blocking work on {@code blocking}. */
    default AsyncCryptoKey async(Executor blocking) {
        return AsyncCryptoKey.onExecutor(this, blocking);
    }

    /** The native client, such as {@code KmsClient}, {@code CryptographyClient} or {@code KeyManagementServiceClient}. */
    <T> T unwrap(Class<T> nativeType);
}
