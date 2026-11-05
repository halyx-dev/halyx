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
import dev.halyx.keys.AsyncKeyStore;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.DataKey;
import dev.halyx.keys.KeyMetadata;
import dev.halyx.keys.KeyOptions;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreStatistics;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.WrappedKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * A {@link KeyStore} as {@code Mono} and {@code Flux}: each call runs when subscribed, once per subscription.
 * {@code decrypt}, {@code decryptDataKey}, {@code keys} and {@code ping} use the store's
 * {@link KeyStore#async() asynchronous view}, non-blocking where the provider's driver is; {@link #decryptBody} runs
 * on the scheduler of the {@link ReactiveHalyx} that made it. Errors are the blocking API's {@code CloudException}s.
 */
public final class ReactiveKeyStore {

    private final KeyStore store;
    private final Calls calls;
    // One reactive handle per blocking handle, so equal options give the same key here too.
    private final Map<CryptoKey, ReactiveCryptoKey> keys = new ConcurrentHashMap<>();

    ReactiveKeyStore(KeyStore store, Calls calls) {
        this.store = Objects.requireNonNull(store, "store");
        this.calls = calls;
    }

    public String provider() {
        return store.provider();
    }

    /** The store's location, as configured; empty for the provider's default. */
    public Optional<String> location() {
        return store.location();
    }

    public Capabilities capabilities() {
        return store.capabilities();
    }

    public KeyStoreStatistics statistics() {
        return store.statistics();
    }

    /** The blocking store this wraps. */
    public KeyStore blocking() {
        return store;
    }

    /** The key {@code name}; makes no key service call. */
    public ReactiveCryptoKey key(String name) {
        return wrap(store.key(name));
    }

    /** The key {@code name}, used as {@code options} say. Asking again with equal options returns the same key. */
    public ReactiveCryptoKey key(String name, KeyOptions options) {
        return wrap(store.key(name, options));
    }

    ReactiveCryptoKey wrap(CryptoKey key) {
        return keys.computeIfAbsent(key, k -> new ReactiveCryptoKey(k, this, calls));
    }

    /**
     * The store's asynchronous view for one call, whose blocking work (where the driver has no non-blocking path) runs
     * on {@code blocking}: the scheduler, with the subscriber's context restored.
     */
    private AsyncKeyStore async(Executor blocking) {
        AsyncKeyStore view = store.async(blocking);
        return view != null ? view : AsyncKeyStore.onExecutor(store, blocking); // a mock or proxy without one
    }

    /** The metadata of every key whose name starts with {@code prefix} (empty for all). */
    public Flux<KeyMetadata> keys(String prefix) {
        return calls.future(blocking -> async(blocking).keys(prefix)).flatMapIterable(list -> list);
    }

    /** Opens data sealed under any key of this store; see {@link KeyStore#decrypt}. */
    public Mono<byte[]> decrypt(SealedData sealed, byte[] aad) {
        return calls.future(blocking -> async(blocking).decrypt(sealed, aad));
    }

    /** Unwraps a data key that a key of this store wrapped. Close it when done; a cancelled one is closed for you. */
    public Mono<DataKey> decryptDataKey(WrappedKey wrapped) {
        return calls.future(blocking -> async(blocking).decryptDataKey(wrapped))
                .doOnDiscard(DataKey.class, DataKey::close);
    }

    /**
     * The plaintext of sealed data arriving as {@code sealed}, opened one segment at a time, in chunks of up to
     * {@link ReactiveBlobStore#DEFAULT_CHUNK_SIZE} bytes, as the subscriber asks for them: for data too large to hold
     * in memory. {@code sealed} is read a few chunks at a time, so a slow subscriber holds back its publisher.
     * Fails with {@code DataIntegrityException} at the first changed segment, or at the end if the data was truncated,
     * so a consumer must not act on the plaintext until the {@code Flux} completes.
     */
    public Flux<ByteBuffer> decryptBody(Publisher<ByteBuffer> sealed, byte[] aad) {
        Objects.requireNonNull(sealed, "sealed");
        Objects.requireNonNull(aad, "aad");
        return SealedBodies.open(calls, sealed, in -> store.decryptingStream(in, aad));
    }

    /** Completes when the key service answers an authenticated read, past every cache; for health checks. */
    public Mono<Void> ping() {
        return calls.future(blocking -> async(blocking).ping()).then();
    }

    public <T> T unwrap(Class<T> nativeType) {
        return store.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveKeyStore[" + store + "]";
    }
}
