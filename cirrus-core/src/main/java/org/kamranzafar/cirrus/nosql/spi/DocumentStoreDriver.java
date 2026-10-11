package org.kamranzafar.cirrus.nosql.spi;

import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.nosql.Document;
import org.kamranzafar.cirrus.nosql.DocumentUpdate;
import org.kamranzafar.cirrus.nosql.Index;
import org.kamranzafar.cirrus.nosql.IndexQuery;
import org.kamranzafar.cirrus.nosql.Item;
import org.kamranzafar.cirrus.nosql.Key;
import org.kamranzafar.cirrus.nosql.Query;
import org.kamranzafar.cirrus.nosql.QueryPage;
import org.kamranzafar.cirrus.nosql.TableConfig;
import org.kamranzafar.cirrus.nosql.Version;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    /** {@code index} is the table's declared index named by the query. */
    QueryPage queryIndex(Index index, IndexQuery query);

    CloudException translate(Throwable error);

    <T> T unwrap(Class<T> nativeType);

    @Override
    default void close() {
    }
}
