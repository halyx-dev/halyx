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
package dev.halyx.nosql;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of a {@link DocumentStore}, from {@link DocumentStore#async()}: the same operations and
 * failures, as {@code CompletableFuture}s. Where the provider's driver has a non-blocking path (DynamoDB does), no
 * thread waits for the network; elsewhere the call runs on the blocking executor. Checks, interceptors and error
 * translation are those of the blocking calls; a failed future holds the {@code CloudException} the blocking call
 * would throw. Batch reads and writes and transactions are on {@link #blocking()}.
 */
public interface AsyncDocumentStore {

    String provider();

    TableConfig table();

    CompletableFuture<Optional<Item>> get(Key key);

    default CompletableFuture<Item> put(Key key, Document document) {
        return put(key, document, WriteOptions.defaults());
    }

    CompletableFuture<Item> put(Key key, Document document, WriteOptions options);

    default CompletableFuture<Item> update(Key key, DocumentUpdate update) {
        return update(key, update, WriteOptions.defaults());
    }

    CompletableFuture<Item> update(Key key, DocumentUpdate update, WriteOptions options);

    default CompletableFuture<Boolean> delete(Key key) {
        return delete(key, null);
    }

    /** Deletes the item, only if it still has {@code ifVersion} when that isn't null; true if there was one. */
    CompletableFuture<Boolean> delete(Key key, Version ifVersion);

    CompletableFuture<QueryPage> query(Query query);

    CompletableFuture<QueryPage> queryIndex(IndexQuery query);

    /** The blocking store this is a view of, for the operations this view doesn't have. */
    DocumentStore blocking();

    /** A view that runs each of {@code store}'s blocking calls on {@code executor}: for stores without one of their own. */
    static AsyncDocumentStore onExecutor(DocumentStore store, Executor executor) {
        return new BlockingAsyncDocumentStore(Objects.requireNonNull(store, "store"),
                Objects.requireNonNull(executor, "executor"));
    }
}
