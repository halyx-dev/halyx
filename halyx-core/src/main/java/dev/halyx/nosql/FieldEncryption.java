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
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.DataKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.WrappedKey;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Client-side encryption of a table's {@link EncryptedField}s. Each call of the document store opens a
 * {@link Session}, which keeps the data keys it generated or unwrapped until the call returns, then zeroes them:
 * <ul>
 *   <li>a document write generates one data key per key it uses (normally one), shared by all its encrypted fields,
 *       each still sealed on its own with its own salt, nonce prefix and AAD;</li>
 *   <li>a read unwraps each distinct data key once, however many fields and items share it, and a page's data keys
 *       {@value #PARALLEL_UNWRAPS} at a time.</li>
 * </ul>
 * Nothing outlives the call; the key store's opt-in data-key cache is what saves calls across reads.
 */
final class FieldEncryption {

    /** At most this many key service calls at once for one store call. */
    static final int PARALLEL_UNWRAPS = 16;

    private final TableConfig table;
    private final String provider;

    FieldEncryption(TableConfig table, String provider) {
        this.table = table;
        this.provider = provider;
    }

    boolean enabled() {
        return !table.encryptedFields().isEmpty();
    }

    /** Refuses increments of encrypted fields: the store can't add to a value it can't read. */
    void checkUpdate(DocumentUpdate update) {
        for (String field : update.increment().keySet()) {
            if (table.encryptedFields().containsKey(field)) {
                throw new InvalidArgumentException("Field '" + field + "' is encrypted on " + table.name()
                        + " and can't be incremented; read it, change it and set it", null, provider, null);
            }
        }
    }

    Session session(Executor blocking) {
        return new Session(blocking);
    }

    /** The store an entry list is opened with: fields sealed with one data key share it. */
    private record Sealer(KeyStore store, List<WrappedKey> entries) {
    }

    /** One store call's data keys. Thread-safe; close it when the call completes. */
    final class Session implements AutoCloseable {

        private final Executor blocking;
        private final Map<Sealer, CompletableFuture<DataKey>> known = new ConcurrentHashMap<>();
        private final List<DataKey> held = new ArrayList<>();
        private boolean closed;

        private Session(Executor blocking) {
            this.blocking = blocking;
        }

        /** {@code document} with its encrypted fields sealed. */
        CompletableFuture<Document> seal(Key key, Document document) {
            if (!enabled()) {
                return CompletableFuture.completedFuture(document);
            }
            Map<String, Object> values = new LinkedHashMap<>();
            for (String field : document.fieldNames()) {
                if (table.encryptedFields().containsKey(field)) {
                    values.put(field, document.get(field));
                }
            }
            return sealValues(key, values).thenApply(sealed -> {
                Document.Builder out = document.toBuilder();
                sealed.forEach(out::put);
                return out.build();
            });
        }

        /** {@code update} with the values it sets on encrypted fields sealed. */
        CompletableFuture<DocumentUpdate> seal(Key key, DocumentUpdate update) {
            if (!enabled()) {
                return CompletableFuture.completedFuture(update);
            }
            checkUpdate(update);
            Map<String, Object> values = new LinkedHashMap<>();
            update.set().forEach((field, value) -> {
                if (table.encryptedFields().containsKey(field)) {
                    values.put(field, value);
                }
            });
            if (values.isEmpty()) {
                return CompletableFuture.completedFuture(update);
            }
            return sealValues(key, values).thenApply(sealed -> {
                DocumentUpdate.Builder out = DocumentUpdate.builder();
                update.set().forEach((field, value) -> out.set(field, sealed.containsKey(field) ? sealed.get(field) : value));
                update.remove().forEach(out::remove);
                update.increment().forEach((field, amount) -> {
                    if (amount instanceof Long whole) {
                        out.increment(field, whole);
                    } else {
                        out.increment(field, amount.doubleValue());
                    }
                });
                return out.build();
            });
        }

        /** Seals each document in turn, {@value #PARALLEL_UNWRAPS} at a time: one data key per document. */
        CompletableFuture<Map<Key, Document>> sealAll(Map<Key, Document> documents) {
            if (!enabled()) {
                return CompletableFuture.completedFuture(documents);
            }
            List<Key> keys = List.copyOf(documents.keySet());
            List<Supplier<CompletableFuture<Document>>> calls = new ArrayList<>();
            for (Key key : keys) {
                calls.add(() -> seal(key, documents.get(key)));
            }
            return bounded(calls).thenApply(sealed -> {
                Map<Key, Document> out = new LinkedHashMap<>();
                for (int i = 0; i < keys.size(); i++) {
                    out.put(keys.get(i), sealed.get(i));
                }
                return out;
            });
        }

        /** One data key per key the fields use (normally one), each field sealed with its own AAD. */
        private CompletableFuture<Map<String, byte[]>> sealValues(Key key, Map<String, Object> values) {
            if (values.isEmpty()) {
                return CompletableFuture.completedFuture(Map.of());
            }
            Map<CryptoKey, List<String>> byKey = new IdentityHashMap<>();
            try {
                for (String field : values.keySet()) {
                    CryptoKey cryptoKey = table.encryptedFields().get(field).key(key);
                    byKey.computeIfAbsent(cryptoKey, k -> new ArrayList<>()).add(field);
                }
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            Map<String, byte[]> sealed = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> writes = new ArrayList<>();
            byKey.forEach((cryptoKey, fields) -> writes.add(cryptoKey.async(blocking).generateDataKey()
                    .thenAccept(dataKey -> {
                        hold(dataKey);
                        known.putIfAbsent(new Sealer(cryptoKey.store(), List.of(dataKey.wrapped())),
                                CompletableFuture.completedFuture(dataKey));
                        for (String field : fields) {
                            byte[] plaintext = FieldCodec.encode(values.get(field));
                            sealed.put(field, dataKey.seal(plaintext, FieldCodec.aad(table.name(), key, field))
                                    .toBytes());
                        }
                    })));
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).thenApply(done -> sealed);
        }

        CompletableFuture<Optional<Item>> open(Optional<Item> item) {
            if (!enabled() || item.isEmpty()) {
                return CompletableFuture.completedFuture(item);
            }
            return open(item.get()).thenApply(Optional::of);
        }

        CompletableFuture<Item> open(Item item) {
            if (!enabled()) {
                return CompletableFuture.completedFuture(item);
            }
            return open(List.of(item)).thenApply(List::getFirst);
        }

        CompletableFuture<QueryPage> open(QueryPage page) {
            if (!enabled() || page.items().isEmpty()) {
                return CompletableFuture.completedFuture(page);
            }
            return open(page.items()).thenApply(items -> new QueryPage(items, page.nextPageToken()));
        }

        CompletableFuture<Map<Key, Item>> open(Map<Key, Item> items) {
            if (!enabled() || items.isEmpty()) {
                return CompletableFuture.completedFuture(items);
            }
            List<Key> keys = List.copyOf(items.keySet());
            return open(keys.stream().map(items::get).toList()).thenApply(opened -> {
                Map<Key, Item> out = new LinkedHashMap<>();
                for (int i = 0; i < keys.size(); i++) {
                    out.put(keys.get(i), opened.get(i));
                }
                return out;
            });
        }

        /**
         * Opens the encrypted fields of {@code items}: first every distinct data key they need, at most
         * {@value #PARALLEL_UNWRAPS} key service calls at a time, then each field locally.
         */
        CompletableFuture<List<Item>> open(List<Item> items) {
            if (!enabled()) {
                return CompletableFuture.completedFuture(items);
            }
            List<Map<String, SealedData>> sealedPerItem = new ArrayList<>(items.size());
            Map<Sealer, Boolean> needed = new LinkedHashMap<>();
            try {
                for (Item item : items) {
                    Map<String, SealedData> sealed = sealedFields(item);
                    sealedPerItem.add(sealed);
                    sealed.forEach((field, data) -> needed.put(sealer(field, data), Boolean.TRUE));
                }
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            List<Supplier<CompletableFuture<DataKey>>> unwraps = new ArrayList<>();
            for (Sealer sealer : needed.keySet()) {
                unwraps.add(() -> dataKey(sealer));
            }
            return bounded(unwraps).thenApply(done -> {
                List<Item> out = new ArrayList<>(items.size());
                for (int i = 0; i < items.size(); i++) {
                    Item item = items.get(i);
                    Map<String, SealedData> sealed = sealedPerItem.get(i);
                    if (sealed.isEmpty()) {
                        out.add(item);
                        continue;
                    }
                    Document.Builder document = item.document().toBuilder();
                    sealed.forEach((field, data) -> {
                        DataKey dataKey = known.get(sealer(field, data)).join();
                        byte[] plaintext = dataKey.open(data, FieldCodec.aad(table.name(), item.key(), field));
                        document.put(field, FieldCodec.decode(plaintext));
                    });
                    out.add(new Item(item.key(), document.build(), item.version(), item.expiresAt()));
                }
                return out;
            });
        }

        /** The item's sealed encrypted fields; a value stored in the clear is refused, never returned. */
        private Map<String, SealedData> sealedFields(Item item) {
            Map<String, SealedData> sealed = new LinkedHashMap<>();
            for (String field : table.encryptedFields().keySet()) {
                Object value = item.document().get(field);
                if (value == null) {
                    continue; // absent: an item written before the field was encrypted, or never set
                }
                if (!(value instanceof byte[] bytes)) {
                    throw new InvalidArgumentException("Field '" + field + "' of " + table.name() + "/" + item.key()
                            + " is encrypted on this table but stored in the clear (" + value.getClass().getSimpleName()
                            + "); rewrite the item through the store to encrypt it", null, provider, null);
                }
                sealed.put(field, SealedData.parse(bytes));
            }
            return sealed;
        }

        private Sealer sealer(String field, SealedData data) {
            return new Sealer(table.encryptedFields().get(field).store(), data.keys());
        }

        /** The data key of {@code sealer}, unwrapped once per session. */
        private CompletableFuture<DataKey> dataKey(Sealer sealer) {
            return known.computeIfAbsent(sealer, s -> unwrap(s, 0).whenComplete((dataKey, error) -> {
                if (dataKey != null) {
                    hold(dataKey);
                }
            }));
        }

        /** Unwraps with the first entry the field's store opens; data sealed for several keys lists several. */
        private CompletableFuture<DataKey> unwrap(Sealer sealer, int entry) {
            return sealer.store().async(blocking).decryptDataKey(sealer.entries().get(entry))
                    .exceptionallyCompose(error -> {
                        Throwable cause = AsyncInvocation.unwrap(error);
                        return cause instanceof InvalidArgumentException && entry + 1 < sealer.entries().size()
                                ? unwrap(sealer, entry + 1) : CompletableFuture.failedFuture(cause);
                    });
        }

        /** Runs {@code calls} at most {@value #PARALLEL_UNWRAPS} at a time; the results in order. */
        private <T> CompletableFuture<List<T>> bounded(List<Supplier<CompletableFuture<T>>> calls) {
            List<T> results = new ArrayList<>(calls.size());
            for (int i = 0; i < calls.size(); i++) {
                results.add(null);
            }
            AtomicInteger next = new AtomicInteger();
            int workers = Math.min(PARALLEL_UNWRAPS, calls.size());
            CompletableFuture<?>[] running = new CompletableFuture<?>[workers];
            for (int w = 0; w < workers; w++) {
                running[w] = worker(calls, results, next);
            }
            return CompletableFuture.allOf(running).thenApply(done -> results);
        }

        private <T> CompletableFuture<Void> worker(List<Supplier<CompletableFuture<T>>> calls, List<T> results,
                                                   AtomicInteger next) {
            int i = next.getAndIncrement();
            if (i >= calls.size()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<T> call;
            try {
                call = calls.get(i).get();
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
            return call.thenCompose(result -> {
                synchronized (results) {
                    results.set(i, result);
                }
                return worker(calls, results, next);
            });
        }

        private void hold(DataKey dataKey) {
            boolean late;
            synchronized (this) {
                late = closed;
                if (!late) {
                    held.add(dataKey);
                }
            }
            if (late) {
                dataKey.close(); // finished after the call gave up on it
            }
        }

        /** Zeroes every data key of the call. */
        @Override
        public void close() {
            List<DataKey> keys;
            synchronized (this) {
                closed = true;
                keys = List.copyOf(held);
                held.clear();
            }
            keys.forEach(DataKey::close);
        }
    }
}
