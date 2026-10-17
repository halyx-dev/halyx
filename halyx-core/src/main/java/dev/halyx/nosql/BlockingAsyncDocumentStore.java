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

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** {@link DocumentStore#async()} for stores other than Halyx' own: each blocking call on the executor. */
record BlockingAsyncDocumentStore(DocumentStore blocking, Executor executor) implements AsyncDocumentStore {

    @Override
    public String provider() {
        return blocking.provider();
    }

    @Override
    public TableConfig table() {
        return blocking.table();
    }

    @Override
    public CompletableFuture<Optional<Item>> get(Key key) {
        return run(() -> blocking.get(key));
    }

    @Override
    public CompletableFuture<Item> put(Key key, Document document, WriteOptions options) {
        return run(() -> blocking.put(key, document, options));
    }

    @Override
    public CompletableFuture<Item> update(Key key, DocumentUpdate update, WriteOptions options) {
        return run(() -> blocking.update(key, update, options));
    }

    @Override
    public CompletableFuture<Boolean> delete(Key key, Version ifVersion) {
        return run(() -> blocking.delete(key, ifVersion));
    }

    @Override
    public CompletableFuture<QueryPage> query(Query query) {
        return run(() -> blocking.query(query));
    }

    @Override
    public CompletableFuture<QueryPage> queryIndex(IndexQuery query) {
        return run(() -> blocking.queryIndex(query));
    }

    private <T> CompletableFuture<T> run(Supplier<T> call) {
        return CompletableFuture.supplyAsync(call, executor);
    }
}
