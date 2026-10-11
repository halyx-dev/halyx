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
package org.kamranzafar.cirrus.gcp.firestore;

import com.google.api.core.ApiFuture;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Blob;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreException;
import com.google.cloud.firestore.Precondition;
import com.google.cloud.firestore.WriteBatch;
import io.grpc.Status;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.error.UnavailableException;
import org.kamranzafar.cirrus.error.UnknownCloudException;
import org.kamranzafar.cirrus.gcp.GcpErrors;
import org.kamranzafar.cirrus.gcp.GcpProvider;
import org.kamranzafar.cirrus.nosql.Document;
import org.kamranzafar.cirrus.nosql.DocumentUpdate;
import org.kamranzafar.cirrus.nosql.Index;
import org.kamranzafar.cirrus.nosql.IndexQuery;
import org.kamranzafar.cirrus.nosql.Item;
import org.kamranzafar.cirrus.nosql.Key;
import org.kamranzafar.cirrus.nosql.Query;
import org.kamranzafar.cirrus.nosql.QueryPage;
import org.kamranzafar.cirrus.nosql.SortCondition;
import org.kamranzafar.cirrus.nosql.TableConfig;
import org.kamranzafar.cirrus.nosql.Version;
import org.kamranzafar.cirrus.nosql.spi.DocumentStoreDriver;
import org.kamranzafar.cirrus.nosql.spi.TransactionWrite;
import org.kamranzafar.cirrus.nosql.spi.WriteCondition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * A Firestore collection. Without a sort key, each item is the document {@code <table>/<partition>}; with
 * one, {@code <table>/<partition>/<table>/<sort>}, so a partition query reads one subcollection and needs no
 * composite index, and an index query is a collection-group query on the table's name (it needs a composite
 * index on the index attributes). Ids escape {@code %} and {@code /}, which keeps ordering for all other
 * keys. Items of sorted tables also keep their sort key in {@value #SORT}, which queries order and filter on
 * (Firestore can't scan document ids in descending order); its automatic single-field index is enough. The
 * version is the document's update time. Expiry is the Timestamp field {@value #EXPIRES}: give the collection
 * group a TTL policy on it to have expired items deleted. Decimals are stored as doubles.
 */
public final class FirestoreDocumentStoreDriver implements DocumentStoreDriver {

    static final String EXPIRES = "cirrus_expires";
    static final String SORT = "cirrus_sort";
    /** Table property: the subcollection name under each partition (default: the table's name). */
    public static final String ITEMS_COLLECTION = "gcp.firestore.items-collection";

    private final Firestore firestore;
    private final TableConfig table;
    private final String items;

    public FirestoreDocumentStoreDriver(Firestore firestore, TableConfig table) {
        this.firestore = firestore;
        this.table = table;
        // The subcollection shares the table's name, so a collection-group query by that name sees this table only.
        this.items = table.property(ITEMS_COLLECTION).orElse(table.name());
    }

    @Override
    public String provider() {
        return GcpProvider.ID;
    }

    @Override
    public TableConfig table() {
        return table;
    }

    @Override
    public Set<String> reservedFields() {
        return table.hasSortKey() ? Set.of(EXPIRES, SORT) : Set.of(EXPIRES);
    }

    /** Document ids may not contain '/', or be '.', '..' or __x__; escaping '%' keeps the scheme reversible. */
    static String encode(String key) {
        String escaped = key.replace("%", "%25").replace("/", "%2F");
        if (escaped.equals(".") || escaped.equals("..") || escaped.startsWith("__")) {
            escaped = escaped.replace(".", "%2E").replaceFirst("^__", "%5F%5F");
        }
        return escaped;
    }

    static String decode(String id) {
        return id.replace("%2F", "/").replace("%2E", ".").replace("%5F", "_").replace("%25", "%");
    }

    private CollectionReference partitionItems(String partition) {
        return firestore.collection(table.name()).document(encode(partition)).collection(items);
    }

    private DocumentReference ref(Key key) {
        return key.sort().isPresent()
                ? partitionItems(key.partition()).document(encode(key.sort().get()))
                : firestore.collection(table.name()).document(encode(key.partition()));
    }

    private Key keyOf(DocumentReference ref) {
        if (table.hasSortKey()) {
            return Key.of(decode(ref.getParent().getParent().getId()), decode(ref.getId()));
        }
        return Key.of(decode(ref.getId()));
    }

    private static <T> T await(ApiFuture<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UnknownCloudException("Firestore call failed", e.getCause(), GcpProvider.ID, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnknownCloudException("Interrupted calling Firestore", e, GcpProvider.ID, null);
        }
    }

    private static Version version(Timestamp updated) {
        return new Version(updated.getSeconds() + "." + String.format("%09d", updated.getNanos()));
    }

    private static Timestamp timestamp(Version version) {
        String[] parts = version.value().split("\\.");
        try {
            return Timestamp.ofTimeSecondsAndNanos(Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
        } catch (RuntimeException e) {
            throw new PreconditionFailedException("Not a Firestore version: " + version);
        }
    }

    private Item toItem(DocumentSnapshot snapshot) {
        Map<String, Object> data = snapshot.getData() == null ? Map.of() : snapshot.getData();
        Document.Builder b = Document.builder();
        Instant expires = null;
        for (var entry : data.entrySet()) {
            if (entry.getKey().equals(SORT)) {
                continue;
            }
            if (entry.getKey().equals(EXPIRES)) {
                if (entry.getValue() instanceof Timestamp ts) {
                    expires = ts.toDate().toInstant();
                }
                continue;
            }
            b.put(entry.getKey(), fromFirestore(entry.getValue()));
        }
        return new Item(keyOf(snapshot.getReference()), b.build(), version(snapshot.getUpdateTime()), Optional.ofNullable(expires));
    }

    private static Object fromFirestore(Object value) {
        return switch (value) {
            case null -> null;
            case Blob blob -> blob.toBytes();
            case Timestamp ts -> ts.toString();
            case Map<?, ?> map -> {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), fromFirestore(v)));
                yield out;
            }
            case List<?> list -> list.stream().map(FirestoreDocumentStoreDriver::fromFirestore).toList();
            default -> value;
        };
    }

    private static Object toFirestore(Object value) {
        return switch (value) {
            case byte[] bytes -> Blob.fromBytes(bytes);
            case BigDecimal bd -> bd.doubleValue();
            case Document d -> {
                Map<String, Object> out = new LinkedHashMap<>();
                d.fieldNames().forEach(f -> out.put(f, toFirestore(d.get(f))));
                yield out;
            }
            case List<?> list -> list.stream().map(FirestoreDocumentStoreDriver::toFirestore).toList();
            case null -> null;
            default -> value;
        };
    }

    private static Map<String, Object> data(Key key, Document document, Instant expiresAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        document.fieldNames().forEach(f -> data.put(f, toFirestore(document.get(f))));
        key.sort().ifPresent(sort -> data.put(SORT, sort));
        if (expiresAt != null) {
            data.put(EXPIRES, Timestamp.of(java.util.Date.from(expiresAt)));
        }
        return data;
    }

    @Override
    public Optional<Item> get(Key key) {
        DocumentSnapshot snapshot = await(ref(key).get());
        return snapshot.exists() ? Optional.of(toItem(snapshot)) : Optional.empty();
    }

    @Override
    public Version put(Key key, Document document, WriteCondition condition, Instant expiresAt) {
        DocumentReference ref = ref(key);
        Map<String, Object> data = data(key, document, expiresAt);
        return switch (condition) {
            case WriteCondition.None ignored -> version(await(ref.set(data)).getUpdateTime());
            case WriteCondition.NotExists ignored -> {
                try {
                    yield version(await(ref.create(data)).getUpdateTime());
                } catch (RuntimeException e) {
                    if (codeOf(e) == Status.Code.ALREADY_EXISTS) {
                        throw new PreconditionFailedException("Item exists: " + key, e, GcpProvider.ID, "ALREADY_EXISTS");
                    }
                    throw e;
                }
            }
            case WriteCondition.IfVersion v -> {
                // A full replace with a precondition: check and write in one transaction.
                Timestamp expected = timestamp(v.version());
                await(firestore.runTransaction(tx -> {
                    DocumentSnapshot current = tx.get(ref).get();
                    if (!current.exists() || !current.getUpdateTime().equals(expected)) {
                        throw new PreconditionFailedException("Item " + key + " is not at version " + v.version());
                    }
                    tx.set(ref, data);
                    return null;
                }));
                yield get(key).map(Item::version).orElseThrow(() -> new NotFoundException("Item " + key + " vanished"));
            }
        };
    }

    @Override
    public Item update(Key key, DocumentUpdate update, Optional<Version> ifVersion, Instant expiresAt) {
        List<Object> fieldsAndValues = new ArrayList<>();
        update.set().forEach((field, value) -> {
            fieldsAndValues.add(FieldPath.of(field));
            fieldsAndValues.add(toFirestore(value));
        });
        update.remove().forEach(field -> {
            fieldsAndValues.add(FieldPath.of(field));
            fieldsAndValues.add(FieldValue.delete());
        });
        update.increment().forEach((field, amount) -> {
            fieldsAndValues.add(FieldPath.of(field));
            fieldsAndValues.add(amount instanceof Double d ? FieldValue.increment(d) : FieldValue.increment(amount.longValue()));
        });
        if (expiresAt != null) {
            fieldsAndValues.add(FieldPath.of(EXPIRES));
            fieldsAndValues.add(Timestamp.of(java.util.Date.from(expiresAt)));
        }
        FieldPath first = (FieldPath) fieldsAndValues.get(0);
        Object firstValue = fieldsAndValues.get(1);
        Object[] rest = fieldsAndValues.subList(2, fieldsAndValues.size()).toArray();
        try {
            // An update without a precondition already requires the document to exist.
            await(ifVersion.isPresent()
                    ? ref(key).update(Precondition.updatedAt(timestamp(ifVersion.get())), first, firstValue, rest)
                    : ref(key).update(first, firstValue, rest));
        } catch (RuntimeException e) {
            throw preconditionOrNotFound(key, e);
        }
        return get(key).orElseThrow(() -> new NotFoundException("Item " + key + " vanished"));
    }

    private RuntimeException preconditionOrNotFound(Key key, RuntimeException e) {
        Status.Code code = codeOf(e);
        if (code == Status.Code.NOT_FOUND) {
            return new NotFoundException("No item " + key, e, GcpProvider.ID, code.name());
        }
        if (code == Status.Code.FAILED_PRECONDITION) {
            return get(key).isEmpty()
                    ? new NotFoundException("No item " + key, e, GcpProvider.ID, code.name())
                    : new PreconditionFailedException("Item " + key + " changed", e, GcpProvider.ID, code.name());
        }
        return e;
    }

    @Override
    public boolean delete(Key key, Optional<Version> ifVersion) {
        DocumentReference ref = ref(key);
        if (ifVersion.isPresent()) {
            try {
                await(ref.delete(Precondition.updatedAt(timestamp(ifVersion.get()))));
                return true;
            } catch (RuntimeException e) {
                Status.Code code = codeOf(e);
                if (code == Status.Code.FAILED_PRECONDITION || code == Status.Code.NOT_FOUND) {
                    throw new PreconditionFailedException("Item " + key + " is not at version " + ifVersion.get(),
                            e, GcpProvider.ID, code.name());
                }
                throw e;
            }
        }
        // Firestore's delete doesn't say whether there was a document: read it, then delete that version.
        DocumentSnapshot current = await(ref.get());
        if (!current.exists()) {
            return false;
        }
        try {
            await(ref.delete(Precondition.updatedAt(current.getUpdateTime())));
        } catch (RuntimeException e) {
            if (codeOf(e) != Status.Code.FAILED_PRECONDITION && codeOf(e) != Status.Code.NOT_FOUND) {
                throw e;
            }
            await(ref.delete()); // changed or removed meanwhile: delete whatever is there now
        }
        return true;
    }

    @Override
    public Map<Key, Item> getAll(List<Key> keys) {
        DocumentReference[] refs = keys.stream().map(this::ref).toArray(DocumentReference[]::new);
        Map<Key, Item> found = new LinkedHashMap<>();
        for (DocumentSnapshot snapshot : await(firestore.getAll(refs))) {
            if (snapshot.exists()) {
                Item item = toItem(snapshot);
                found.put(item.key(), item);
            }
        }
        return found;
    }

    @Override
    public int maxBatchGet() {
        return 100;
    }

    @Override
    public void putAll(Map<Key, Document> items) {
        WriteBatch batch = firestore.batch();
        items.forEach((key, document) -> batch.set(ref(key), data(key, document, null)));
        await(batch.commit());
    }

    @Override
    public int maxBatchWrite() {
        return 500;
    }

    @Override
    public QueryPage query(Query query) {
        var direction = query.descending() ? com.google.cloud.firestore.Query.Direction.DESCENDING
                : com.google.cloud.firestore.Query.Direction.ASCENDING;
        com.google.cloud.firestore.Query q = partitionItems(query.partitionKey()).orderBy(SORT, direction);
        if (query.condition().isPresent()) {
            switch (query.condition().get()) {
                case SortCondition.BeginsWith b -> q = q.whereGreaterThanOrEqualTo(SORT, b.prefix())
                        .whereLessThan(SORT, b.prefix() + "\uffff");
                case SortCondition.Between b -> q = q.whereGreaterThanOrEqualTo(SORT, b.from()).whereLessThanOrEqualTo(SORT, b.to());
                case SortCondition.Compare c -> q = switch (c.operator()) {
                    case GT -> q.whereGreaterThan(SORT, c.value());
                    case GE -> q.whereGreaterThanOrEqualTo(SORT, c.value());
                    case LT -> q.whereLessThan(SORT, c.value());
                    case LE -> q.whereLessThanOrEqualTo(SORT, c.value());
                };
            }
        }
        if (query.pageToken().isPresent()) {
            q = q.startAfter(new String(Base64.getUrlDecoder().decode(query.pageToken().get()),
                    StandardCharsets.UTF_8));
        }
        // One extra tells whether another page follows.
        var documents = await(q.limit(query.limit() + 1).get()).getDocuments();
        List<Item> page = new ArrayList<>();
        for (int i = 0; i < Math.min(query.limit(), documents.size()); i++) {
            page.add(toItem(documents.get(i)));
        }
        Optional<String> next = documents.size() > query.limit()
                ? Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(
                        page.getLast().key().sort().orElseThrow().getBytes(StandardCharsets.UTF_8)))
                : Optional.empty();
        return new QueryPage(page, next);
    }

    /**
     * A Firestore transaction: read every item, check the conditions (an expired item counts as absent), then
     * write. Firestore doesn't return update times from transactions, so new versions are read afterwards.
     */
    @Override
    public Map<Key, Version> transact(List<TransactionWrite> operations) {
        List<DocumentReference> refs = operations.stream().map(op -> ref(op.key())).toList();
        Instant now = Instant.now();
        await(firestore.runTransaction(tx -> {
            List<DocumentSnapshot> snapshots = tx.getAll(refs.toArray(DocumentReference[]::new)).get();
            for (int i = 0; i < operations.size(); i++) {
                TransactionWrite op = operations.get(i);
                DocumentSnapshot current = snapshots.get(i);
                boolean exists = current.exists() && toItem(current).expiresAt().map(now::isBefore).orElse(true);
                Optional<Version> required = switch (op) {
                    case TransactionWrite.Put put -> put.condition() instanceof WriteCondition.IfVersion v ? Optional.of(v.version()) : Optional.empty();
                    case TransactionWrite.Update update -> update.ifVersion();
                    case TransactionWrite.Delete delete -> delete.ifVersion();
                    case TransactionWrite.Check check -> Optional.of(check.version());
                };
                boolean needsExisting = required.isPresent() || op instanceof TransactionWrite.Update;
                if (op instanceof TransactionWrite.Put put && put.condition() instanceof WriteCondition.NotExists && exists) {
                    throw new PreconditionFailedException("Item exists: " + op.key());
                }
                if (needsExisting && !exists) {
                    throw new PreconditionFailedException("No item " + op.key());
                }
                if (required.isPresent() && !version(current.getUpdateTime()).equals(required.get())) {
                    throw new PreconditionFailedException("Item " + op.key() + " is not at version " + required.get());
                }
            }
            for (int i = 0; i < operations.size(); i++) {
                DocumentReference ref = refs.get(i);
                switch (operations.get(i)) {
                    case TransactionWrite.Put put -> tx.set(ref, data(put.key(), put.document(), put.expiresAt()));
                    case TransactionWrite.Update update -> {
                        List<Object> changes = changes(update.update(), update.expiresAt());
                        tx.update(ref, (FieldPath) changes.get(0), changes.get(1), changes.subList(2, changes.size()).toArray());
                    }
                    case TransactionWrite.Delete ignored -> tx.delete(ref);
                    case TransactionWrite.Check ignored -> { }
                }
            }
            return null;
        }));
        Map<Key, Version> versions = new LinkedHashMap<>();
        List<Key> written = operations.stream()
                .filter(op -> op instanceof TransactionWrite.Put || op instanceof TransactionWrite.Update)
                .map(TransactionWrite::key).toList();
        if (!written.isEmpty()) {
            getAll(written).forEach((key, item) -> versions.put(key, item.version()));
        }
        return versions;
    }

    /** Field paths and values for an update, as Firestore's update(...) takes them. */
    private static List<Object> changes(DocumentUpdate update, Instant expiresAt) {
        List<Object> fieldsAndValues = new ArrayList<>();
        update.set().forEach((field, value) -> {
            fieldsAndValues.add(FieldPath.of(field));
            fieldsAndValues.add(toFirestore(value));
        });
        update.remove().forEach(field -> {
            fieldsAndValues.add(FieldPath.of(field));
            fieldsAndValues.add(FieldValue.delete());
        });
        update.increment().forEach((field, amount) -> {
            fieldsAndValues.add(FieldPath.of(field));
            fieldsAndValues.add(amount instanceof Double d ? FieldValue.increment(d) : FieldValue.increment(amount.longValue()));
        });
        if (expiresAt != null) {
            fieldsAndValues.add(FieldPath.of(EXPIRES));
            fieldsAndValues.add(Timestamp.of(java.util.Date.from(expiresAt)));
        }
        return fieldsAndValues;
    }

    /** A collection-group query on the items' collection id, ordered by the sort attribute, then the id. */
    @Override
    public QueryPage queryIndex(Index index, IndexQuery query) {
        String group = table.hasSortKey() ? items : table.name();
        var direction = query.descending() ? com.google.cloud.firestore.Query.Direction.DESCENDING
                : com.google.cloud.firestore.Query.Direction.ASCENDING;
        com.google.cloud.firestore.Query q = firestore.collectionGroup(group)
                .whereEqualTo(index.partitionAttribute(), query.value());
        if (index.sortAttribute().isPresent()) {
            String sort = index.sortAttribute().get();
            if (query.condition().isPresent()) {
                q = switch (query.condition().get()) {
                    case SortCondition.BeginsWith b -> q.whereGreaterThanOrEqualTo(sort, b.prefix()).whereLessThan(sort, b.prefix() + "\uffff");
                    case SortCondition.Between b -> q.whereGreaterThanOrEqualTo(sort, b.from()).whereLessThanOrEqualTo(sort, b.to());
                    case SortCondition.Compare c -> switch (c.operator()) {
                        case GT -> q.whereGreaterThan(sort, c.value());
                        case GE -> q.whereGreaterThanOrEqualTo(sort, c.value());
                        case LT -> q.whereLessThan(sort, c.value());
                        case LE -> q.whereLessThanOrEqualTo(sort, c.value());
                    };
                };
            }
            q = q.orderBy(sort, direction);
        }
        q = q.orderBy(FieldPath.documentId(), direction);
        if (query.pageToken().isPresent()) {
            String[] cursor = new String(Base64.getUrlDecoder().decode(query.pageToken().get()), StandardCharsets.UTF_8)
                    .split("\n", 2);
            DocumentReference last = firestore.document(cursor[cursor.length - 1]);
            q = index.sortAttribute().isPresent() ? q.startAfter(cursor[0], last) : q.startAfter(last);
        }
        var documents = await(q.limit(query.limit() + 1).get()).getDocuments();
        List<Item> page = new ArrayList<>();
        for (int i = 0; i < Math.min(query.limit(), documents.size()); i++) {
            page.add(toItem(documents.get(i)));
        }
        Optional<String> next = Optional.empty();
        if (documents.size() > query.limit()) {
            DocumentSnapshot last = documents.get(query.limit() - 1);
            String cursor = index.sortAttribute().map(s -> last.getString(s) + "\n").orElse("") + last.getReference().getPath();
            next = Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(cursor.getBytes(StandardCharsets.UTF_8)));
        }
        return new QueryPage(page, next);
    }

    /** The gRPC status of a failure, whether the client reported it as a FirestoreException or a gax ApiException. */
    private static Status.Code codeOf(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof FirestoreException f && f.getStatus() != null) {
                return f.getStatus().getCode();
            }
            if (t instanceof ApiException api) {
                return Status.Code.valueOf(api.getStatusCode().getCode().name());
            }
            if (t instanceof io.grpc.StatusRuntimeException grpc) {
                return grpc.getStatus().getCode();
            }
        }
        return Status.Code.UNKNOWN;
    }

    @Override
    public CloudException translate(Throwable error) {
        if (error instanceof CloudException c) {
            return c;
        }
        Status.Code status = codeOf(error);
        if (status != Status.Code.UNKNOWN) {
            String code = status.name();
            String message = error.getMessage();
            return switch (status) {
                case NOT_FOUND -> new NotFoundException(message, error, GcpProvider.ID, code);
                case ALREADY_EXISTS, FAILED_PRECONDITION, ABORTED -> new PreconditionFailedException(message, error, GcpProvider.ID, code);
                case PERMISSION_DENIED, UNAUTHENTICATED -> new AccessDeniedException(message, error, GcpProvider.ID, code);
                case INVALID_ARGUMENT, OUT_OF_RANGE -> new InvalidArgumentException(message, error, GcpProvider.ID, code);
                case RESOURCE_EXHAUSTED -> new ThrottledException(message, error, GcpProvider.ID, code);
                case UNAVAILABLE, DEADLINE_EXCEEDED, INTERNAL -> new UnavailableException(message, error, GcpProvider.ID, code);
                default -> new UnknownCloudException(message, error, GcpProvider.ID, code);
            };
        }
        return GcpErrors.translate(error).orElseGet(() -> new UnknownCloudException(error.getMessage(), error, GcpProvider.ID, null));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        if (nativeType.isInstance(firestore)) {
            return nativeType.cast(firestore);
        }
        throw new IllegalArgumentException("No native client of type " + nativeType.getName());
    }
}
