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
package dev.halyx.nosql.spi;

import dev.halyx.error.CloudException;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentUpdate;
import dev.halyx.nosql.Index;
import dev.halyx.nosql.IndexQuery;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.QueryPage;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.Version;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * One table on one provider. Core validates keys and documents, filters expired items and splits batches;
 * drivers return items with their expiry as stored, expired or not.
 */
public interface DocumentStoreDriver extends AutoCloseable {

    String provider();

    TableConfig table();

    /** Field names the driver stores alongside documents, which documents may not use. */
    Set<String> reservedFields();

    Optional<Item> get(Key key);

    /** Writes the whole item; {@code expiresAt} is null for no expiry. Returns the new version. */
    Version put(Key key, Document document, WriteCondition condition, Instant expiresAt);

    /**
     * Applies {@code update} to an existing item ({@code NotFoundException} otherwise) and returns the result.
     * {@code expiresAt} replaces the expiry when not null.
     */
    Item update(Key key, DocumentUpdate update, Optional<Version> ifVersion, Instant expiresAt);

    /** True if an item was deleted. */
    boolean delete(Key key, Optional<Version> ifVersion);

    /** Up to {@link #maxBatchGet()} keys. */
    Map<Key, Item> getAll(List<Key> keys);

    int maxBatchGet();

    /** Up to {@link #maxBatchWrite()} items, unconditionally. */
    void putAll(Map<Key, Document> items);

    int maxBatchWrite();

    /** Needs a table with a sort key. */
    QueryPage query(Query query);

    /**
     * Applies {@code operations} (one partition, validated by core) atomically; conditions are already
     * resolved. Returns the new versions of written items. Any failed condition is a
     * {@code PreconditionFailedException}.
     */
    Map<Key, Version> transact(List<TransactionWrite> operations);

    /** {@code index} is the table's declared index named by the query. */
    QueryPage queryIndex(Index index, IndexQuery query);

    // --- asynchronous calls ------------------------------------------------------------------------
    // Each defaults to its blocking call on the blocking executor; a driver whose SDK has non-blocking clients
    // overrides them. Failures may be returned or thrown; the API layer translates both.

    default CompletableFuture<Optional<Item>> getAsync(Key key, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> get(key), blocking);
    }

    default CompletableFuture<Version> putAsync(Key key, Document document, WriteCondition condition, Instant expiresAt,
                                                Executor blocking) {
        return CompletableFuture.supplyAsync(() -> put(key, document, condition, expiresAt), blocking);
    }

    default CompletableFuture<Item> updateAsync(Key key, DocumentUpdate update, Optional<Version> ifVersion,
                                                Instant expiresAt, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> update(key, update, ifVersion, expiresAt), blocking);
    }

    default CompletableFuture<Boolean> deleteAsync(Key key, Optional<Version> ifVersion, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> delete(key, ifVersion), blocking);
    }

    default CompletableFuture<QueryPage> queryAsync(Query query, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> query(query), blocking);
    }

    default CompletableFuture<QueryPage> queryIndexAsync(Index index, IndexQuery query, Executor blocking) {
        return CompletableFuture.supplyAsync(() -> queryIndex(index, query), blocking);
    }

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    @Override
    default void close() {
    }
}
