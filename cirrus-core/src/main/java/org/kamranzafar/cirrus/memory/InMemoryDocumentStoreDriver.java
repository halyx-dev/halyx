package org.kamranzafar.cirrus.memory;

import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.error.UnknownCloudException;
import org.kamranzafar.cirrus.nosql.Document;
import org.kamranzafar.cirrus.nosql.DocumentUpdate;
import org.kamranzafar.cirrus.nosql.Item;
import org.kamranzafar.cirrus.nosql.Key;
import org.kamranzafar.cirrus.nosql.Query;
import org.kamranzafar.cirrus.nosql.QueryPage;
import org.kamranzafar.cirrus.nosql.TableConfig;
import org.kamranzafar.cirrus.nosql.Version;
import org.kamranzafar.cirrus.nosql.spi.DocumentStoreDriver;
import org.kamranzafar.cirrus.nosql.spi.WriteCondition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/** Tables in memory: partitions of items sorted by sort key, with versions from a counter. */
final class InMemoryDocumentStoreDriver implements DocumentStoreDriver {

    /** Rows of a table: partition key, then sort key ("" when the table has none). */
    static final class Table {
        final ConcurrentSkipListMap<String, ConcurrentSkipListMap<String, Item>> partitions = new ConcurrentSkipListMap<>();
        final AtomicLong versions = new AtomicLong();
    }

    private final TableConfig config;
    private final Table table;

    InMemoryDocumentStoreDriver(TableConfig config, Table table) {
        this.config = config;
        this.table = table;
    }

    @Override
    public String provider() {
        return InMemoryProvider.ID;
    }

    @Override
    public TableConfig table() {
        return config;
    }

    @Override
    public Set<String> reservedFields() {
        return Set.of();
    }

    private ConcurrentSkipListMap<String, Item> partition(Key key) {
        return table.partitions.computeIfAbsent(key.partition(), p -> new ConcurrentSkipListMap<>());
    }

    private static String sort(Key key) {
        return key.sort().orElse("");
    }

    @Override
    public Optional<Item> get(Key key) {
        var partition = table.partitions.get(key.partition());
        return partition == null ? Optional.empty() : Optional.ofNullable(partition.get(sort(key)));
    }

    @Override
    public Version put(Key key, Document document, WriteCondition condition, Instant expiresAt) {
        synchronized (table) {
            Item current = get(key).orElse(null);
            check(key, current, condition);
            Version version = next();
            partition(key).put(sort(key), new Item(key, document, version, Optional.ofNullable(expiresAt)));
            return version;
        }
    }

    private static void check(Key key, Item current, WriteCondition condition) {
        switch (condition) {
            case WriteCondition.None ignored -> { }
            case WriteCondition.NotExists ignored -> {
                if (current != null) {
                    throw new PreconditionFailedException("Item exists: " + key);
                }
            }
            case WriteCondition.IfVersion v -> {
                if (current == null || !current.version().equals(v.version())) {
                    throw new PreconditionFailedException("Item " + key + " is not at version " + v.version());
                }
            }
        }
    }

    private Version next() {
        return new Version(String.valueOf(table.versions.incrementAndGet()));
    }

    @Override
    public Item update(Key key, DocumentUpdate update, Optional<Version> ifVersion, Instant expiresAt) {
        synchronized (table) {
            Item current = get(key).orElseThrow(() -> new NotFoundException("No item " + key));
            ifVersion.ifPresent(v -> check(key, current, new WriteCondition.IfVersion(v)));
            Document.Builder b = current.document().toBuilder();
            update.set().forEach(b::put);
            update.remove().forEach(b::remove);
            Document before = current.document();
            update.increment().forEach((field, amount) -> b.put(field, add(before.get(field), amount)));
            Item updated = new Item(key, b.build(), next(),
                    expiresAt != null ? Optional.of(expiresAt) : current.expiresAt());
            partition(key).put(sort(key), updated);
            return updated;
        }
    }

    static Object add(Object current, Number amount) {
        return switch (current) {
            case null -> amount instanceof Double d ? d : amount.longValue();
            case Long l when amount instanceof Long a -> l + a;
            case BigDecimal bd -> bd.add(new BigDecimal(amount.toString()));
            case Number n -> n.doubleValue() + amount.doubleValue();
            default -> throw new IllegalArgumentException("Cannot increment a non-number: " + current);
        };
    }

    @Override
    public boolean delete(Key key, Optional<Version> ifVersion) {
        synchronized (table) {
            Item current = get(key).orElse(null);
            if (ifVersion.isPresent()) {
                check(key, current, new WriteCondition.IfVersion(ifVersion.get()));
            }
            return current != null && partition(key).remove(sort(key)) != null;
        }
    }

    @Override
    public Map<Key, Item> getAll(List<Key> keys) {
        Map<Key, Item> out = new LinkedHashMap<>();
        keys.forEach(k -> get(k).ifPresent(i -> out.put(k, i)));
        return out;
    }

    @Override
    public int maxBatchGet() {
        return 100;
    }

    @Override
    public void putAll(Map<Key, Document> items) {
        items.forEach((key, document) -> put(key, document, WriteCondition.NONE, null));
    }

    @Override
    public int maxBatchWrite() {
        return 25;
    }

    @Override
    public QueryPage query(Query query) {
        var partition = table.partitions.get(query.partitionKey());
        if (partition == null) {
            return new QueryPage(List.of(), Optional.empty());
        }
        NavigableMap<String, Item> view = query.descending() ? partition.descendingMap() : partition;
        if (query.pageToken().isPresent()) {
            view = view.tailMap(query.pageToken().get(), false);
        }
        List<Item> items = new ArrayList<>();
        String last = null;
        for (var entry : view.entrySet()) {
            if (query.condition().isPresent() && !query.condition().get().test(entry.getKey())) {
                continue;
            }
            if (items.size() == query.limit()) {
                return new QueryPage(items, Optional.of(last));
            }
            items.add(entry.getValue());
            last = entry.getKey();
        }
        return new QueryPage(items, Optional.empty());
    }

    @Override
    public CloudException translate(Throwable error) {
        return error instanceof CloudException c ? c
                : new UnknownCloudException(error.getMessage(), error, InMemoryProvider.ID, null);
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        throw new IllegalArgumentException("The memory provider has no native client");
    }
}
