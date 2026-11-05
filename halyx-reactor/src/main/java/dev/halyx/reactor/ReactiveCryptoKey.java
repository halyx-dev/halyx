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
package dev.halyx.reactor;

import dev.halyx.capability.Capabilities;
import dev.halyx.keys.AsyncCryptoKey;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.DataKey;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.Signature;
import dev.halyx.keys.WrappedKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.security.PublicKey;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * A {@link CryptoKey} as {@code Mono} and {@code Flux}: each call runs when subscribed, once per subscription.
 * {@code metadata}, {@code encrypt}, {@code decrypt}, {@code generateDataKey}, signing and MACs use the key's
 * {@link CryptoKey#async() asynchronous view}, non-blocking where the provider's driver is; the other calls run on the
 * scheduler of the {@link ReactiveHalyx} that made it. Errors are the blocking API's {@code CloudException}s.
 *
 * <pre>{@code
 * ReactiveCryptoKey orders = rx.provider("cloud").keyStore().key("orders-data");
 * Mono<SealedData> sealed = orders.encrypt(json, orderId.getBytes(UTF_8));   // one wrap call per subscription
 * }</pre>
 *
 * Data keys handed out are closed if the subscriber cancels before taking them.
 */
public final class ReactiveCryptoKey {

    private final CryptoKey key;
    private final ReactiveKeyStore store;
    private final Calls calls;

    ReactiveCryptoKey(CryptoKey key, ReactiveKeyStore store, Calls calls) {
        this.key = Objects.requireNonNull(key, "key");
        this.store = store;
        this.calls = calls;
    }

    /** The store this key came from. */
    public ReactiveKeyStore store() {
        return store;
    }

    /** The key's name in its store, without the store's prefix. */
    public String name() {
        return key.name();
    }

    public String provider() {
        return key.provider();
    }

    /** What this key can do: signing, MACs, and the store's features. */
    public Capabilities capabilities() {
        return key.capabilities();
    }

    /** The blocking key this wraps. */
    public CryptoKey blocking() {
        return key;
    }

    Calls calls() {
        return calls;
    }

    /**
     * The key's asynchronous view for one call, whose blocking work (where the driver has no non-blocking path) runs
     * on {@code blocking}: the scheduler, with the subscriber's context restored.
     */
    private AsyncCryptoKey async(Executor blocking) {
        AsyncCryptoKey view = key.async(blocking);
        return view != null ? view : AsyncCryptoKey.onExecutor(key, blocking); // a mock or proxy without one
    }

    /** The key's metadata, read on first use and refreshed every few minutes. */
    public Mono<KeyMetadata> metadata() {
        return calls.future(blocking -> async(blocking).metadata());
    }

    /** Seals {@code plaintext}, bound to {@code aad} (may be empty); see {@link CryptoKey#encrypt}. */
    public Mono<SealedData> encrypt(byte[] plaintext, byte[] aad) {
        return calls.future(blocking -> async(blocking).encrypt(plaintext, aad));
    }

    /** Opens data sealed under this key; see {@link CryptoKey#decrypt}. */
    public Mono<byte[]> decrypt(SealedData sealed, byte[] aad) {
        return calls.future(blocking -> async(blocking).decrypt(sealed, aad));
    }

    /**
     * {@code plaintext} sealed one segment at a time as it arrives, for data too large to hold in memory: the HLXS
     * bytes, ready to store or send. {@code plaintext} is read a few chunks at a time, so a slow subscriber holds back
     * its publisher. Only a {@code plaintext} that completes gives sealed data that opens; if it fails or the
     * subscription is cancelled, what was emitted so far fails to open as truncated.
     */
    public Flux<ByteBuffer> encryptBody(Publisher<ByteBuffer> plaintext, byte[] aad) {
        Objects.requireNonNull(plaintext, "plaintext");
        Objects.requireNonNull(aad, "aad");
        return SealedBodies.seal(calls, plaintext, sink -> key.encryptingStream(sink, aad));
    }

    /** Opens data sealed under this key as it arrives; see {@link ReactiveKeyStore#decryptBody}. */
    public Flux<ByteBuffer> decryptBody(Publisher<ByteBuffer> sealed, byte[] aad) {
        return store.decryptBody(sealed, aad);
    }

    /** A fresh data key and its wrapped form, for applications with their own format. Close it when done. */
    public Mono<DataKey> generateDataKey() {
        return calls.future(blocking -> async(blocking).generateDataKey()).doOnDiscard(DataKey.class, DataKey::close);
    }

    /** Unwraps a data key this key wrapped. Close it when done. */
    public Mono<DataKey> decryptDataKey(WrappedKey wrapped) {
        return calls.mono(() -> key.decryptDataKey(wrapped)).doOnDiscard(DataKey.class, DataKey::close);
    }

    /** Wraps an existing data key with this key, such as one from another key's {@link #generateDataKey()}. */
    public Mono<WrappedKey> wrap(DataKey dataKey) {
        return calls.mono(() -> key.wrap(dataKey));
    }

    /** Rewraps sealed data from any key of this key's store onto this key's current version. Only the header changes. */
    public Mono<SealedData> rewrap(SealedData sealed) {
        return calls.mono(() -> key.rewrap(sealed));
    }

    /** Rewraps sealed data from a key of {@code from}, which may be on another provider or cloud, onto this key. */
    public Mono<SealedData> rewrap(SealedData sealed, ReactiveKeyStore from) {
        Objects.requireNonNull(from, "from");
        return calls.mono(() -> key.rewrap(sealed, from.blocking()));
    }

    /** Adds this key to sealed data that {@code from} can open, so either can open it. Only the header changes. */
    public Mono<SealedData> addTo(SealedData sealed, ReactiveKeyStore from) {
        Objects.requireNonNull(from, "from");
        return calls.mono(() -> key.addTo(sealed, from.blocking()));
    }

    /** Hashes {@code data} locally and has the key service sign the digest. */
    public Mono<Signature> sign(byte[] data) {
        return calls.future(blocking -> async(blocking).sign(data));
    }

    /** Signs a digest already computed with the signing algorithm's hash. */
    public Mono<Signature> signDigest(byte[] digest) {
        return calls.future(blocking -> async(blocking).signDigest(digest));
    }

    /** Verifies {@code signature} over {@code data} locally; no key service call once the public key is cached. */
    public Mono<Boolean> verify(byte[] data, Signature signature) {
        return calls.future(blocking -> async(blocking).verify(data, signature));
    }

    /** HMAC-SHA256 of {@code data} with the key (requires {@code KeyCapability.MAC}). */
    public Mono<byte[]> mac(byte[] data) {
        return calls.future(blocking -> async(blocking).mac(data));
    }

    /** Whether {@code mac} is the key's HMAC-SHA256 of {@code data} (requires {@code KeyCapability.MAC}). */
    public Mono<Boolean> verifyMac(byte[] data, byte[] mac) {
        return calls.future(blocking -> async(blocking).verifyMac(data, mac));
    }

    /** The public key of a signing key's {@code version}. */
    public Mono<PublicKey> publicKey(String version) {
        return calls.mono(() -> key.publicKey(version));
    }

    /** The public key of the current version. */
    public Mono<PublicKey> currentPublicKey() {
        return calls.mono(key::currentPublicKey);
    }

    /** The public key of every enabled version, by version (one entry under {@code ""} where versions aren't visible). */
    public Mono<Map<String, PublicKey>> publicKeys() {
        return calls.mono(key::publicKeys);
    }

    public <T> T unwrap(Class<T> nativeType) {
        return key.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveCryptoKey[" + key + "]";
    }
}
