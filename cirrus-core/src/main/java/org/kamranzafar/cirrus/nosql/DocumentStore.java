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

    /** The items that exist among {@code keys}, read in as few requests as the store allows. */
    Map<Key, Item> getAll(Collection<Key> keys);

    /** Creates or replaces many items in batches. Not atomic: on failure, earlier batches stay written. */
    void putAll(Map<Key, Document> items);

    QueryPage query(Query query);

    /** Every page of {@code query}, fetched as the stream is consumed. */
    default Stream<Item> queryAll(Query query) {
        return Stream.iterate(query(query),
                        page -> page != null,
                        page -> page.nextPageToken().map(t -> query(query.withPageToken(t))).orElse(null))
                .flatMap(page -> page.items().stream());
    }

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
