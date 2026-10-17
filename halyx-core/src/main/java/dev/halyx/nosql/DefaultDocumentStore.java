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

import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.PreconditionFailedException;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.nosql.spi.DocumentStoreDriver;
import dev.halyx.nosql.spi.TransactionWrite;
import dev.halyx.nosql.spi.WriteCondition;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
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

    private final AsyncDocumentStore async = new Async(InterceptorChain.blocking());

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
            // If it is already gone (the store's TTL sweep got there first), just retry.
            Optional<Item> existing = execute("get", key, true, () -> driver.get(key));
            if (existing.isPresent() && live(existing.get())) {
                throw e;
            }
            if (existing.isPresent()) {
                try {
                    execute("delete", key, false, () -> driver.delete(key, Optional.of(existing.get().version())));
                } catch (PreconditionFailedException replaced) {
                    throw e; // someone wrote a new item meanwhile
                }
            }
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
    public Map<Key, Version> transact(Transaction transaction) {
        Objects.requireNonNull(transaction, "transaction");
        Instant now = clock.instant();
        List<TransactionWrite> writes = new ArrayList<>();
        for (Transaction.Operation operation : transaction.operations()) {
            checkKey(operation.key());
            writes.add(switch (operation) {
                case Transaction.Put put -> {
                    checkDocument(put.document());
                    WriteCondition condition = put.options().notExists() ? new WriteCondition.NotExists()
                            : put.options().version().<WriteCondition>map(WriteCondition.IfVersion::new).orElse(WriteCondition.NONE);
                    yield new TransactionWrite.Put(put.key(), put.document(), condition, put.options().expiresAt(now));
                }
                case Transaction.Update update -> {
                    update.update().fields().forEach(this::checkField);
                    yield new TransactionWrite.Update(update.key(), update.update(), update.options().version(),
                            update.options().expiresAt(now));
                }
                case Transaction.Delete delete -> new TransactionWrite.Delete(delete.key(), delete.ifVersion());
                case Transaction.Check check -> new TransactionWrite.Check(check.key(), check.version());
            });
        }
        return execute("transact", Key.of(transaction.partition()), false, () -> driver.transact(writes));
    }

    @Override
    public QueryPage queryIndex(IndexQuery query) {
        Objects.requireNonNull(query, "query");
        Index index = table().index(query.index()).orElseThrow(() -> new IllegalArgumentException(
                "Table " + table().name() + " has no index '" + query.index() + "'; declare it with TableConfig.index"));
        if (query.condition().isPresent() && index.sortAttribute().isEmpty()) {
            throw new IllegalArgumentException("Index " + index.name() + " has no sort attribute to put a condition on");
        }
        QueryPage page = execute("queryIndex", Key.of(index.name()), true, () -> driver.queryIndex(index, query));
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
                    + " (key attributes and Halyx' own: " + reserved + ")");
        }
    }

    @Override
    public AsyncDocumentStore async() {
        return async;
    }

    @Override
    public AsyncDocumentStore async(Executor blocking) {
        return blocking == InterceptorChain.blocking() ? async : new Async(Objects.requireNonNull(blocking, "blocking"));
    }

    /** The asynchronous {@link #execute}: the driver's future through the interceptors, its failure translated first. */
    private <T> CompletableFuture<T> executeAsync(String operation, Key key, boolean idempotent, Executor blocking,
                                                  Function<Executor, CompletableFuture<T>> call) {
        OperationContext context = new OperationContext(SERVICE, operation, provider(), table().name() + "/" + key, idempotent);
        return interceptors.executeAsync(context, () -> {
            CompletableFuture<T> future;
            try {
                future = call.apply(blocking);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(driver.translate(e));
            }
            return future.exceptionallyCompose(error -> {
                Throwable cause = AsyncInvocation.unwrap(error);
                return CompletableFuture.failedFuture(cause instanceof RuntimeException r ? driver.translate(r) : cause);
            });
        }, blocking);
    }

    /** Runs the argument checks, then the call: a check that fails gives a failed future, as the call would. */
    private static <T> CompletableFuture<T> checked(Runnable checks, Supplier<CompletableFuture<T>> call) {
        try {
            checks.run();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return call.get();
    }

    /** The live items of a page, as the blocking queries return. */
    private QueryPage livePage(QueryPage page) {
        List<Item> live = page.items().stream().filter(this::live).toList();
        return live.size() == page.items().size() ? page : new QueryPage(live, page.nextPageToken());
    }

    /**
     * The asynchronous view: the same checks as the blocking calls, then the driver's non-blocking path where it has
     * one; blocking work runs on {@link #blocking}.
     */
    private final class Async implements AsyncDocumentStore {

        private final Executor blocking;

        Async(Executor blocking) {
            this.blocking = blocking;
        }

        @Override
        public String provider() {
            return DefaultDocumentStore.this.provider();
        }

        @Override
        public TableConfig table() {
            return DefaultDocumentStore.this.table();
        }

        @Override
        public CompletableFuture<Optional<Item>> get(Key key) {
            return checked(() -> checkKey(key), () -> executeAsync("get", key, true, blocking,
                    executor -> driver.getAsync(key, executor)).thenApply(item -> item.filter(DefaultDocumentStore.this::live)));
        }

        @Override
        public CompletableFuture<Item> put(Key key, Document document, WriteOptions options) {
            return checked(() -> {
                checkKey(key);
                checkDocument(document);
                Objects.requireNonNull(options, "options");
            }, () -> {
                Instant expiresAt = options.expiresAt(clock.instant());
                WriteCondition condition = options.notExists() ? new WriteCondition.NotExists()
                        : options.version().<WriteCondition>map(WriteCondition.IfVersion::new).orElse(WriteCondition.NONE);
                boolean idempotent = condition instanceof WriteCondition.None;
                return executeAsync("put", key, idempotent, blocking,
                        executor -> driver.putAsync(key, document, condition, expiresAt, executor))
                        .exceptionallyCompose(error -> retryOverExpired(key, document, condition, expiresAt,
                                AsyncInvocation.unwrap(error)))
                        .thenApply(version -> new Item(key, document, version, Optional.ofNullable(expiresAt)));
            });
        }

        /**
         * As the blocking put: an expired item the store hasn't removed yet still blocks a create-only write, so it
         * is removed and the write retried once.
         */
        private CompletableFuture<Version> retryOverExpired(Key key, Document document, WriteCondition condition,
                                                            Instant expiresAt, Throwable failure) {
            if (!(failure instanceof PreconditionFailedException e) || !(condition instanceof WriteCondition.NotExists)) {
                return CompletableFuture.failedFuture(failure);
            }
            return executeAsync("get", key, true, blocking, executor -> driver.getAsync(key, executor))
                    .thenCompose(existing -> {
                        if (existing.isPresent() && live(existing.get())) {
                            return CompletableFuture.<Version>failedFuture(e);
                        }
                        CompletableFuture<Boolean> cleared = existing.isEmpty() ? CompletableFuture.completedFuture(true)
                                : executeAsync("delete", key, false, blocking, executor -> driver.deleteAsync(key,
                                        Optional.of(existing.get().version()), executor))
                                .exceptionallyCompose(error -> CompletableFuture.failedFuture(
                                        // someone wrote a new item meanwhile
                                        AsyncInvocation.unwrap(error) instanceof PreconditionFailedException ? e
                                                : AsyncInvocation.unwrap(error)));
                        return cleared.thenCompose(done -> executeAsync("put", key, false, blocking,
                                executor -> driver.putAsync(key, document, condition, expiresAt, executor)));
                    });
        }

        @Override
        public CompletableFuture<Item> update(Key key, DocumentUpdate update, WriteOptions options) {
            return checked(() -> {
                checkKey(key);
                Objects.requireNonNull(update, "update");
                Objects.requireNonNull(options, "options");
                if (options.notExists()) {
                    throw new IllegalArgumentException("update needs an existing item; use put with ifNotExists");
                }
                update.fields().forEach(DefaultDocumentStore.this::checkField);
            }, () -> {
                Instant expiresAt = options.expiresAt(clock.instant());
                boolean idempotent = update.increment().isEmpty() && options.version().isEmpty();
                return executeAsync("update", key, idempotent, blocking,
                        executor -> driver.updateAsync(key, update, options.version(), expiresAt, executor))
                        .thenApply(item -> {
                            if (!live(item)) {
                                throw new NotFoundException("No item " + key + " (expired)", null, provider(), null);
                            }
                            return item;
                        });
            });
        }

        @Override
        public CompletableFuture<Boolean> delete(Key key, Version ifVersion) {
            return checked(() -> checkKey(key), () -> executeAsync("delete", key, true, blocking,
                    executor -> driver.deleteAsync(key, Optional.ofNullable(ifVersion), executor)));
        }

        @Override
        public CompletableFuture<QueryPage> query(Query query) {
            return checked(() -> {
                Objects.requireNonNull(query, "query");
                if (!table().hasSortKey()) {
                    throw new InvalidArgumentException("Queries need a table with a sort key; " + table().name()
                            + " has none", null, provider(), null);
                }
            }, () -> executeAsync("query", Key.of(query.partitionKey()), true, blocking,
                    executor -> driver.queryAsync(query, executor)).thenApply(DefaultDocumentStore.this::livePage));
        }

        @Override
        public CompletableFuture<QueryPage> queryIndex(IndexQuery query) {
            Index[] index = new Index[1];
            return checked(() -> {
                Objects.requireNonNull(query, "query");
                index[0] = table().index(query.index()).orElseThrow(() -> new IllegalArgumentException(
                        "Table " + table().name() + " has no index '" + query.index() + "'; declare it with TableConfig.index"));
                if (query.condition().isPresent() && index[0].sortAttribute().isEmpty()) {
                    throw new IllegalArgumentException("Index " + index[0].name() + " has no sort attribute to put a condition on");
                }
            }, () -> executeAsync("queryIndex", Key.of(index[0].name()), true, blocking,
                    executor -> driver.queryIndexAsync(index[0], query, executor)).thenApply(DefaultDocumentStore.this::livePage));
        }

        @Override
        public DocumentStore blocking() {
            return DefaultDocumentStore.this;
        }

        @Override
        public String toString() {
            return "Async" + DefaultDocumentStore.this;
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
