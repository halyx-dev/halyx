package org.kamranzafar.cirrus.memory;

import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.error.UnknownCloudException;
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
import org.kamranzafar.cirrus.nosql.spi.DocumentStoreDriver;
import org.kamranzafar.cirrus.nosql.spi.TransactionWrite;
import org.kamranzafar.cirrus.nosql.spi.WriteCondition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** Checks every condition first, then applies all writes, under the table's lock. */
    @Override
    public Map<Key, Version> transact(List<TransactionWrite> operations) {
        synchronized (table) {
            Instant now = Instant.now();
            for (TransactionWrite op : operations) {
                Item current = get(op.key()).filter(i -> i.expiresAt().map(now::isBefore).orElse(true)).orElse(null);
                switch (op) {
                    case TransactionWrite.Put put -> check(op.key(), current, put.condition());
                    case TransactionWrite.Update update -> {
                        if (current == null) {
                            throw new PreconditionFailedException("No item " + op.key() + " to update");
                        }
                        update.ifVersion().ifPresent(v -> check(op.key(), current, new WriteCondition.IfVersion(v)));
                    }
                    case TransactionWrite.Delete delete ->
                            delete.ifVersion().ifPresent(v -> check(op.key(), current, new WriteCondition.IfVersion(v)));
                    case TransactionWrite.Check c -> check(op.key(), current, new WriteCondition.IfVersion(c.version()));
                }
            }
            Map<Key, Version> versions = new LinkedHashMap<>();
            for (TransactionWrite op : operations) {
                switch (op) {
                    case TransactionWrite.Put put -> versions.put(op.key(),
                            put(op.key(), put.document(), WriteCondition.NONE, put.expiresAt()));
                    case TransactionWrite.Update update -> versions.put(op.key(),
                            update(op.key(), update.update(), Optional.empty(), update.expiresAt()).version());
                    case TransactionWrite.Delete delete -> delete(op.key(), Optional.empty());
                    case TransactionWrite.Check ignored -> { }
                }
            }
            return versions;
        }
    }

    /** A scan: fine for tests and local development. Page tokens are offsets. */
    @Override
    public QueryPage queryIndex(Index index, IndexQuery query) {
        Comparator<Item> order = Comparator.comparing((Item i) -> index.sortAttribute()
                        .map(s -> String.valueOf(i.document().get(s))).orElse(""))
                .thenComparing(i -> i.key().toString());
        if (query.descending()) {
            order = order.reversed();
        }
        List<Item> matches = table.partitions.values().stream().flatMap(p -> p.values().stream())
                .filter(i -> valueEquals(i.document().get(index.partitionAttribute()), query.value()))
                .filter(i -> index.sortAttribute().isEmpty() || i.document().get(index.sortAttribute().get()) instanceof String)
                .filter(i -> query.condition().isEmpty()
                        || query.condition().get().test((String) i.document().get(index.sortAttribute().orElseThrow())))
                .sorted(order).toList();
        int from = query.pageToken().map(Integer::parseInt).orElse(0);
        int to = Math.min(matches.size(), from + query.limit());
        return new QueryPage(matches.subList(Math.min(from, to), to),
                to < matches.size() ? Optional.of(String.valueOf(to)) : Optional.empty());
    }

    private static boolean valueEquals(Object stored, Object wanted) {
        if (stored instanceof Number a && wanted instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
        }
        return stored != null && stored.equals(wanted);
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
