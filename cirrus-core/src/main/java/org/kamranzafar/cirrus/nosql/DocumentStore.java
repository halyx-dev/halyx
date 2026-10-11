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
package org.kamranzafar.cirrus.nosql;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/** A portable table of documents addressed by {@link Key}. Thread-safe; share one per table. */
public interface DocumentStore {

    String provider();

    TableConfig table();

    /** The item, or empty if there is none (or it has expired). Reads are strongly consistent. */
    Optional<Item> get(Key key);

    default Item put(Key key, Document document) {
        return put(key, document, WriteOptions.defaults());
    }

    /** Creates or replaces the whole item. */
    Item put(Key key, Document document, WriteOptions options);

    default Item update(Key key, DocumentUpdate update) {
        return update(key, update, WriteOptions.defaults());
    }

    /**
     * Applies a partial update atomically and returns the updated item. Throws {@code NotFoundException} if
     * there is no item; {@code ifNotExists} is not allowed, and a TTL replaces the item's expiry.
     */
    Item update(Key key, DocumentUpdate update, WriteOptions options);

    /** Deletes the item; true if there was one. */
    default boolean delete(Key key) {
        return delete(key, null);
    }

    /** Deletes the item if it is still at {@code ifVersion} (when not null). */
    boolean delete(Key key, Version ifVersion);

    /**
     * The items that exist among {@code keys}, read in as few requests as the store allows. If the store
     * throttles part of a batch, this throws the retryable {@link org.kamranzafar.cirrus.error.ThrottledException}
     * rather than waiting; the caller decides whether to retry.
     */
    Map<Key, Item> getAll(Collection<Key> keys);

    /**
     * Creates or replaces many items in batches. Not atomic: on failure, the items already written stay
     * written. If the store throttles part of a batch, this throws the retryable
     * {@link org.kamranzafar.cirrus.error.ThrottledException} rather than waiting; retrying the whole call
     * is safe, as it replaces items with the same content.
     */
    void putAll(Map<Key, Document> items);

    QueryPage query(Query query);

    /** Every page of {@code query}, fetched as the stream is consumed. */
    default Stream<Item> queryAll(Query query) {
        return Stream.iterate(query(query),
                        page -> page != null,
                        page -> page.nextPageToken().map(t -> query(query.withPageToken(t))).orElse(null))
                .flatMap(page -> page.items().stream());
    }

    /**
     * Applies the transaction atomically and returns the new version of every item it put or updated.
     * Throws {@code PreconditionFailedException}, with nothing written, if any condition fails.
     */
    Map<Key, Version> transact(Transaction transaction);

    /** Items found through a secondary index declared on the table. */
    QueryPage queryIndex(IndexQuery query);

    /** Every page of {@code query}, fetched as the stream is consumed. */
    default Stream<Item> queryIndexAll(IndexQuery query) {
        return Stream.iterate(queryIndex(query),
                        page -> page != null,
                        page -> page.nextPageToken().map(t -> queryIndex(query.withPageToken(t))).orElse(null))
                .flatMap(page -> page.items().stream());
    }

    /** The native client, such as {@code DynamoDbClient}. */
    <T> T unwrap(Class<T> nativeType);
}
