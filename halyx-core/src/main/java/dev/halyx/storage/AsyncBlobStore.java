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

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of a {@link BlobStore}, from {@link BlobStore#async()}: the same operations and failures,
 * as {@code CompletableFuture}s. Where the provider's driver has a non-blocking path (Amazon S3 does), no thread waits
 * for the network; elsewhere the call runs on a virtual thread. Validation, capability checks, interceptors (retries
 * wait on a timer, not a thread) and error translation are the same as for the blocking calls. A failed future holds
 * the same {@code CloudException} the blocking call would throw. Cancelling a future cancels the call where the SDK
 * allows it, and stops further retries.
 */
public interface AsyncBlobStore {

    String provider();

    String bucket();

    /** {@link BlobStore#put}: content in memory is sent without blocking; other content is read on a virtual thread. */
    CompletableFuture<PutResult> put(BlobKey key, BlobContent content, PutOptions options);

    default CompletableFuture<PutResult> put(BlobKey key, BlobContent content) {
        return put(key, content, PutOptions.defaults());
    }

    /** The whole content of a blob; fails with {@code NotFoundException} if there is none. */
    CompletableFuture<byte[]> getBytes(BlobKey key);

    CompletableFuture<Optional<BlobMetadata>> head(BlobKey key);

    default CompletableFuture<Boolean> exists(BlobKey key) {
        return head(key).thenApply(Optional::isPresent);
    }

    CompletableFuture<Void> delete(BlobKey key, DeleteOptions options);

    default CompletableFuture<Void> delete(BlobKey key) {
        return delete(key, DeleteOptions.defaults());
    }

    /** One page of a listing; continue with the page's token. */
    CompletableFuture<BlobPage> list(ListOptions options);

    /** The blocking store this is a view of, for the operations this view doesn't have. */
    BlobStore blocking();

    /** A view that runs each of {@code store}'s blocking calls on {@code executor}: for stores without one of their own. */
    static AsyncBlobStore onExecutor(BlobStore store, Executor executor) {
        return new BlockingAsyncBlobStore(Objects.requireNonNull(store, "store"), Objects.requireNonNull(executor, "executor"));
    }
}
