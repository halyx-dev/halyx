package org.kamranzafar.cirrus.nosql;

import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.interceptor.OperationContext;
import org.kamranzafar.cirrus.nosql.spi.DocumentStoreDriver;
import org.kamranzafar.cirrus.nosql.spi.WriteCondition;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The portable document store: key and document validation, expiry (expired items are never returned and
 * don't block {@code ifNotExists}), batching and interceptors over a driver.
 */
public final class DefaultDocumentStore implements DocumentStore {

    static final String SERVICE = "nosql";

    private final DocumentStoreDriver driver;
    private final InterceptorChain interceptors;
    private final Clock clock;
    private final Set<String> reserved;

    public DefaultDocumentStore(DocumentStoreDriver driver, InterceptorChain interceptors) {
        this(driver, interceptors, Clock.systemUTC());
    }

    DefaultDocumentStore(DocumentStoreDriver driver, InterceptorChain interceptors, Clock clock) {
        this.driver = Objects.requireNonNull(driver, "driver");
        this.interceptors = Objects.requireNonNull(interceptors, "interceptors");
        this.clock = clock;
        Set<String> names = new LinkedHashSet<>(driver.reservedFields());
        names.add(driver.table().partitionKey());
        driver.table().sortKey().ifPresent(names::add);
        this.reserved = Set.copyOf(names);
    }

    @Override
    public String provider() {
        return driver.provider();
    }

    @Override
    public TableConfig table() {
        return driver.table();
    }

    @Override
    public Optional<Item> get(Key key) {
        checkKey(key);
        return execute("get", key, true, () -> driver.get(key)).filter(this::live);
    }

    @Override
    public Item put(Key key, Document document, WriteOptions options) {
        checkKey(key);
        checkDocument(document);
        Objects.requireNonNull(options, "options");
        Instant expiresAt = options.expiresAt(clock.instant());
        WriteCondition condition = options.notExists() ? new WriteCondition.NotExists()
                : options.version().<WriteCondition>map(WriteCondition.IfVersion::new).orElse(WriteCondition.NONE);
        boolean idempotent = condition instanceof WriteCondition.None;
        Version version;
        try {
            version = execute("put", key, idempotent, () -> driver.put(key, document, condition, expiresAt));
        } catch (PreconditionFailedException e) {
            if (!(condition instanceof WriteCondition.NotExists)) {
                throw e;
            }
            // An expired item the store hasn't removed yet still blocks the condition: remove it and retry once.
            Optional<Item> existing = execute("get", key, true, () -> driver.get(key));
            if (existing.isEmpty() || live(existing.get())) {
                throw e;
            }
            execute("delete", key, false, () -> driver.delete(key, Optional.of(existing.get().version())));
            version = execute("put", key, false, () -> driver.put(key, document, condition, expiresAt));
        }
        return new Item(key, document, version, Optional.ofNullable(expiresAt));
    }

    @Override
    public Item update(Key key, DocumentUpdate update, WriteOptions options) {
        checkKey(key);
        Objects.requireNonNull(update, "update");
        Objects.requireNonNull(options, "options");
        if (options.notExists()) {
            throw new IllegalArgumentException("update needs an existing item; use put with ifNotExists");
        }
        for (String field : update.fields()) {
            checkField(field);
        }
        Instant expiresAt = options.expiresAt(clock.instant());
        boolean idempotent = update.increment().isEmpty() && options.version().isEmpty();
        Item item = execute("update", key, idempotent, () -> driver.update(key, update, options.version(), expiresAt));
        if (!live(item)) {
            throw new NotFoundException("No item " + key + " (expired)", null, provider(), null);
        }
        return item;
    }

    @Override
    public boolean delete(Key key, Version ifVersion) {
        checkKey(key);
        return execute("delete", key, true, () -> driver.delete(key, Optional.ofNullable(ifVersion)));
    }

    @Override
    public Map<Key, Item> getAll(Collection<Key> keys) {
        Objects.requireNonNull(keys, "keys");
        List<Key> unique = List.copyOf(new LinkedHashSet<>(keys));
        unique.forEach(this::checkKey);
        Map<Key, Item> found = new LinkedHashMap<>();
        int size = Math.max(1, driver.maxBatchGet());
        for (int from = 0; from < unique.size(); from += size) {
            List<Key> batch = unique.subList(from, Math.min(from + size, unique.size()));
            Map<Key, Item> items = execute("getAll", batch.getFirst(), true, () -> driver.getAll(batch));
            for (Key key : batch) {
                Item item = items.get(key);
                if (item != null && live(item)) {
                    found.put(key, item);
                }
            }
        }
        return found;
    }

    @Override
    public void putAll(Map<Key, Document> items) {
        Objects.requireNonNull(items, "items");
        items.forEach((key, document) -> {
            checkKey(key);
            checkDocument(document);
        });
        List<Map.Entry<Key, Document>> entries = new ArrayList<>(items.entrySet());
        int size = Math.max(1, driver.maxBatchWrite());
        for (int from = 0; from < entries.size(); from += size) {
            Map<Key, Document> batch = new LinkedHashMap<>();
            entries.subList(from, Math.min(from + size, entries.size())).forEach(e -> batch.put(e.getKey(), e.getValue()));
            execute("putAll", batch.keySet().iterator().next(), true, () -> {
                driver.putAll(batch);
                return null;
            });
        }
    }

    @Override
    public QueryPage query(Query query) {
        Objects.requireNonNull(query, "query");
        if (!table().hasSortKey()) {
            throw new InvalidArgumentException("Queries need a table with a sort key; " + table().name() + " has none",
                    null, provider(), null);
        }
        QueryPage page = execute("query", Key.of(query.partitionKey()), true, () -> driver.query(query));
        List<Item> live = page.items().stream().filter(this::live).toList();
        return live.size() == page.items().size() ? page : new QueryPage(live, page.nextPageToken());
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return driver.unwrap(nativeType);
    }

    private boolean live(Item item) {
        return item.expiresAt().map(at -> at.isAfter(clock.instant())).orElse(true);
    }

    private void checkKey(Key key) {
        Objects.requireNonNull(key, "key");
        if (table().hasSortKey() && key.sort().isEmpty()) {
            throw new IllegalArgumentException("Table " + table().name() + " has a sort key: use Key.of(partition, sort)");
        }
        if (!table().hasSortKey() && key.sort().isPresent()) {
            throw new IllegalArgumentException("Table " + table().name() + " has no sort key: use Key.of(partition)");
        }
    }

    private void checkDocument(Document document) {
        Objects.requireNonNull(document, "document");
        document.fieldNames().forEach(this::checkField);
    }

    private void checkField(String field) {
        if (reserved.contains(field)) {
            throw new IllegalArgumentException("Field '" + field + "' is reserved on " + table().name()
                    + " (key attributes and Cirrus' own: " + reserved + ")");
        }
    }

    private <T> T execute(String operation, Key key, boolean idempotent, Supplier<T> call) {
        OperationContext context = new OperationContext(SERVICE, operation, provider(), table().name() + "/" + key, idempotent);
        return interceptors.execute(context, () -> {
            try {
                return call.get();
            } catch (RuntimeException e) {
                throw driver.translate(e);
            }
        });
    }

    @Override
    public String toString() {
        return "DocumentStore[" + provider() + ":" + table().name() + "]";
    }
}
