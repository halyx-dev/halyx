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

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of a {@link CryptoKey}, from {@link CryptoKey#async()}: the same operations and failures, as
 * {@code CompletableFuture}s. Where the provider's driver has a non-blocking path, no thread waits for the network;
 * local cryptography runs on the thread that completes the call.
 */
public interface AsyncCryptoKey {

    String provider();

    String name();

    CompletableFuture<KeyMetadata> metadata();

    CompletableFuture<SealedData> encrypt(byte[] plaintext, byte[] aad);

    CompletableFuture<byte[]> decrypt(SealedData sealed, byte[] aad);

    CompletableFuture<DataKey> generateDataKey();

    CompletableFuture<Signature> sign(byte[] data);

    CompletableFuture<Signature> signDigest(byte[] digest);

    CompletableFuture<Boolean> verify(byte[] data, Signature signature);

    CompletableFuture<byte[]> mac(byte[] data);

    CompletableFuture<Boolean> verifyMac(byte[] data, byte[] mac);

    /** The blocking key this is a view of. */
    CryptoKey blocking();

    /** A view that runs each of {@code key}'s blocking calls on {@code executor}: for keys without one. */
    static AsyncCryptoKey onExecutor(CryptoKey key, Executor executor) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(executor, "executor");
        return new AsyncCryptoKey() {
            @Override
            public String provider() {
                return key.provider();
            }

            @Override
            public String name() {
                return key.name();
            }

            @Override
            public CompletableFuture<KeyMetadata> metadata() {
                return CompletableFuture.supplyAsync(key::metadata, executor);
            }

            @Override
            public CompletableFuture<SealedData> encrypt(byte[] plaintext, byte[] aad) {
                return CompletableFuture.supplyAsync(() -> key.encrypt(plaintext, aad), executor);
            }

            @Override
            public CompletableFuture<byte[]> decrypt(SealedData sealed, byte[] aad) {
                return CompletableFuture.supplyAsync(() -> key.decrypt(sealed, aad), executor);
            }

            @Override
            public CompletableFuture<DataKey> generateDataKey() {
                return CompletableFuture.supplyAsync(key::generateDataKey, executor);
            }

            @Override
            public CompletableFuture<Signature> sign(byte[] data) {
                return CompletableFuture.supplyAsync(() -> key.sign(data), executor);
            }

            @Override
            public CompletableFuture<Signature> signDigest(byte[] digest) {
                return CompletableFuture.supplyAsync(() -> key.signDigest(digest), executor);
            }

            @Override
            public CompletableFuture<Boolean> verify(byte[] data, Signature signature) {
                return CompletableFuture.supplyAsync(() -> key.verify(data, signature), executor);
            }

            @Override
            public CompletableFuture<byte[]> mac(byte[] data) {
                return CompletableFuture.supplyAsync(() -> key.mac(data), executor);
            }

            @Override
            public CompletableFuture<Boolean> verifyMac(byte[] data, byte[] mac) {
                return CompletableFuture.supplyAsync(() -> key.verifyMac(data, mac), executor);
            }

            @Override
            public CryptoKey blocking() {
                return key;
            }
        };
    }
}
