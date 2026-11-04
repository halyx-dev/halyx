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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of a {@link KeyStore}, from {@link KeyStore#async()}: the same operations and failures, as
 * {@code CompletableFuture}s.
 */
public interface AsyncKeyStore {

    String provider();

    CompletableFuture<byte[]> decrypt(SealedData sealed, byte[] aad);

    CompletableFuture<DataKey> decryptDataKey(WrappedKey wrapped);

    CompletableFuture<List<KeyMetadata>> keys(String prefix);

    CompletableFuture<Void> ping();

    /** The blocking store this is a view of. */
    KeyStore blocking();

    /** A view that runs each of {@code store}'s blocking calls on {@code executor}: for stores without one. */
    static AsyncKeyStore onExecutor(KeyStore store, Executor executor) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(executor, "executor");
        return new AsyncKeyStore() {
            @Override
            public String provider() {
                return store.provider();
            }

            @Override
            public CompletableFuture<byte[]> decrypt(SealedData sealed, byte[] aad) {
                return CompletableFuture.supplyAsync(() -> store.decrypt(sealed, aad), executor);
            }

            @Override
            public CompletableFuture<DataKey> decryptDataKey(WrappedKey wrapped) {
                return CompletableFuture.supplyAsync(() -> store.decryptDataKey(wrapped), executor);
            }

            @Override
            public CompletableFuture<List<KeyMetadata>> keys(String prefix) {
                return CompletableFuture.supplyAsync(() -> store.keys(prefix), executor);
            }

            @Override
            public CompletableFuture<Void> ping() {
                return CompletableFuture.runAsync(store::ping, executor);
            }

            @Override
            public KeyStore blocking() {
                return store;
            }
        };
    }
}
