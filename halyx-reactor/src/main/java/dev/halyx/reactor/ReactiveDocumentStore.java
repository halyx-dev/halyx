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

import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.DocumentUpdate;
import dev.halyx.nosql.IndexQuery;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.QueryPage;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.Transaction;
import dev.halyx.nosql.Version;
import dev.halyx.nosql.WriteOptions;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A {@link DocumentStore} as {@code Mono} and {@code Flux}: each call runs when subscribed, once per
 * subscription, on the scheduler of the {@link ReactiveHalyx} that made it. A missing item is an empty
 * {@code Mono}.
 */
public final class ReactiveDocumentStore {

    private final DocumentStore store;
    private final Calls calls;

    ReactiveDocumentStore(DocumentStore store, Calls calls) {
        this.store = Objects.requireNonNull(store, "store");
        this.calls = calls;
    }

    public String provider() {
        return store.provider();
    }

    public TableConfig table() {
        return store.table();
    }

    /** The blocking store this wraps. */
    public DocumentStore blocking() {
        return store;
    }

    /** The item, or empty if there is none. */
    public Mono<Item> get(Key key) {
        return calls.optional(() -> store.get(key));
    }

    public Mono<Item> put(Key key, Document document) {
        return calls.mono(() -> store.put(key, document));
    }

    public Mono<Item> put(Key key, Document document, WriteOptions options) {
        return calls.mono(() -> store.put(key, document, options));
    }

    public Mono<Item> update(Key key, DocumentUpdate update) {
        return calls.mono(() -> store.update(key, update));
    }

    public Mono<Item> update(Key key, DocumentUpdate update, WriteOptions options) {
        return calls.mono(() -> store.update(key, update, options));
    }

    /** True if there was an item to delete. */
    public Mono<Boolean> delete(Key key) {
        return calls.mono(() -> store.delete(key));
    }

    /** Deletes only if the stored item still has {@code ifVersion}; see {@link DocumentStore#delete(Key, Version)}. */
    public Mono<Boolean> delete(Key key, Version ifVersion) {
        return calls.mono(() -> store.delete(key, ifVersion));
    }

    public Mono<Map<Key, Item>> getAll(Collection<Key> keys) {
        return calls.mono(() -> store.getAll(keys));
    }

    public Mono<Void> putAll(Map<Key, Document> items) {
        return calls.run(() -> store.putAll(items));
    }

    /**
     * Writes {@code items} as they arrive, in batches of up to {@code batchSize} (a partial batch waits at most
     * {@code maxWait}), one batch at a time, through {@link DocumentStore#putAll}. Emits each item's key once its
     * batch is written; a failed batch fails the {@code Flux}, and earlier batches stay written.
     */
    public Flux<Key> putAll(Publisher<? extends Map.Entry<Key, Document>> items, int batchSize, Duration maxWait) {
        return calls.<Map.Entry<Key, Document>, Key>batches(items, batchSize, maxWait, batch -> () -> {
            Map<Key, Document> byKey = new LinkedHashMap<>();
            batch.forEach(entry -> byKey.put(entry.getKey(), entry.getValue()));
            store.putAll(byKey);
            return List.copyOf(byKey.keySet());
        });
    }

    /** {@link #putAll(Publisher, int, Duration)} with batches of 100, waiting at most 50 ms. */
    public Flux<Key> putAll(Publisher<? extends Map.Entry<Key, Document>> items) {
        return putAll(items, ReactiveHalyx.DEFAULT_BATCH_SIZE, ReactiveHalyx.DEFAULT_BATCH_WAIT);
    }

    /** One page of results. */
    public Mono<QueryPage> query(Query query) {
        return calls.mono(() -> store.query(query));
    }

    /** Every result, page by page as the subscriber asks: at most one page ahead of its demand, none after cancel. */
    public Flux<Item> queryAll(Query query) {
        return calls.pages(() -> store.query(query),
                page -> page.nextPageToken().map(token -> () -> store.query(query.withPageToken(token))),
                QueryPage::items);
    }

    public Mono<QueryPage> queryIndex(IndexQuery query) {
        return calls.mono(() -> store.queryIndex(query));
    }

    /** Every index result, page by page, as {@link #queryAll} reads them. */
    public Flux<Item> queryIndexAll(IndexQuery query) {
        return calls.pages(() -> store.queryIndex(query),
                page -> page.nextPageToken().map(token -> () -> store.queryIndex(query.withPageToken(token))),
                QueryPage::items);
    }

    public Mono<Map<Key, Version>> transact(Transaction transaction) {
        return calls.mono(() -> store.transact(transaction));
    }

    public <T> T unwrap(Class<T> nativeType) {
        return store.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveDocumentStore[" + store + "]";
    }
}
