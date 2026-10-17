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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** {@link BlobStore#async()} for stores other than Halyx' own: each blocking call on the executor. */
record BlockingAsyncBlobStore(BlobStore blocking, Executor executor) implements AsyncBlobStore {

    @Override
    public String provider() {
        return blocking.provider();
    }

    @Override
    public String bucket() {
        return blocking.bucket();
    }

    @Override
    public CompletableFuture<PutResult> put(BlobKey key, BlobContent content, PutOptions options) {
        return run(() -> blocking.put(key, content, options));
    }

    @Override
    public CompletableFuture<byte[]> getBytes(BlobKey key) {
        return run(() -> blocking.getBytes(key));
    }

    @Override
    public CompletableFuture<Optional<BlobMetadata>> head(BlobKey key) {
        return run(() -> blocking.head(key));
    }

    /** The store's own {@code exists}, which may differ from a {@code head}. */
    @Override
    public CompletableFuture<Boolean> exists(BlobKey key) {
        return run(() -> blocking.exists(key));
    }

    @Override
    public CompletableFuture<Void> delete(BlobKey key, DeleteOptions options) {
        return run(() -> {
            blocking.delete(key, options);
            return null;
        });
    }

    @Override
    public CompletableFuture<BlobPage> list(ListOptions options) {
        return run(() -> blocking.list(options));
    }

    private <T> CompletableFuture<T> run(Supplier<T> call) {
        return CompletableFuture.supplyAsync(call, executor);
    }
}
