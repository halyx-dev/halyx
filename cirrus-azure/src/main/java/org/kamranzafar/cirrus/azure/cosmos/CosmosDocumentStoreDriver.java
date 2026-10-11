package org.kamranzafar.cirrus.azure.cosmos;

import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosException;
import com.azure.cosmos.models.CosmosItemIdentity;
import com.azure.cosmos.models.CosmosItemRequestOptions;
import com.azure.cosmos.models.CosmosPatchItemRequestOptions;
import com.azure.cosmos.models.CosmosPatchOperations;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.SqlParameter;
import com.azure.cosmos.models.SqlQuerySpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kamranzafar.cirrus.azure.AzureProvider;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.error.CloudException;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.PreconditionFailedException;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.error.UnavailableException;
import org.kamranzafar.cirrus.error.UnknownCloudException;
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
import org.kamranzafar.cirrus.nosql.spi.WriteCondition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * A Cosmos DB (NoSQL API) container partitioned on {@code /<partitionKey>}. Each item's id is its sort key
 * (or its partition key, in tables without one), escaped because ids may not contain {@code / \ ? #}. The
 * version is the item's ETag. Expiry uses Cosmos' own {@code ttl}, so the container needs a default TTL
 * ({@code -1} turns TTL on without expiring other items); {@value #EXPIRES} keeps the exact time.
 */
public final class CosmosDocumentStoreDriver implements DocumentStoreDriver {

    static final String EXPIRES = "cirrus_expires";
    private static final Set<String> SYSTEM = Set.of("id", "ttl", "_rid", "_self", "_etag", "_attachments", "_ts", "_lsn");

    private final CosmosContainer container;
    private final TableConfig table;

    public CosmosDocumentStoreDriver(CosmosContainer container, TableConfig table) {
        this.container = container;
        this.table = table;
    }

    @Override
    public String provider() {
        return AzureProvider.ID;
    }

    @Override
    public TableConfig table() {
        return table;
    }

    @Override
    public Set<String> reservedFields() {
        Set<String> names = new HashSet<>(SYSTEM);
        names.add(EXPIRES);
        return names;
    }

    private Set<String> internal() {
        Set<String> names = reservedFields();
        names.add(table.partitionKey());
        table.sortKey().ifPresent(names::add);
        return names;
    }

    static String encodeId(String key) {
        return key.replace("%", "%25").replace("/", "%2F").replace("\\", "%5C").replace("?", "%3F").replace("#", "%23");
    }

    private static String id(Key key) {
        return encodeId(key.sort().orElse(key.partition()));
    }

    private ObjectNode toNode(Key key, Document document, Instant expiresAt) {
        ObjectNode node = CosmosValues.toJson(document, Set.of());
        node.put("id", id(key));
        node.put(table.partitionKey(), key.partition());
        table.sortKey().ifPresent(sk -> node.put(sk, key.sort().orElseThrow()));
        if (expiresAt != null) {
            node.put("ttl", Math.max(1, Duration.between(Instant.now(), expiresAt).toSeconds() + 1));
            node.put(EXPIRES, expiresAt.toEpochMilli());
        }
        return node;
    }

    private Item toItem(ObjectNode node, String etag) {
        String partition = node.get(table.partitionKey()).textValue();
        Key key = table.sortKey().map(sk -> Key.of(partition, node.get(sk).textValue())).orElseGet(() -> Key.of(partition));
        JsonNode expires = node.get(EXPIRES);
        String version = etag != null ? etag : node.path("_etag").asText("");
        return new Item(key, CosmosValues.fromJson(node, internal()), new Version(version),
                expires == null || expires.isNull() ? Optional.empty() : Optional.of(Instant.ofEpochMilli(expires.longValue())));
    }

    @Override
    public Optional<Item> get(Key key) {
        try {
            var response = container.readItem(id(key), new PartitionKey(key.partition()), ObjectNode.class);
            return Optional.of(toItem(response.getItem(), response.getETag()));
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Version put(Key key, Document document, WriteCondition condition, Instant expiresAt) {
        ObjectNode node = toNode(key, document, expiresAt);
        PartitionKey pk = new PartitionKey(key.partition());
        try {
            var response = switch (condition) {
                case WriteCondition.None ignored -> container.upsertItem(node, pk, new CosmosItemRequestOptions());
                case WriteCondition.NotExists ignored -> container.createItem(node, pk, new CosmosItemRequestOptions());
                case WriteCondition.IfVersion v -> container.replaceItem(node, id(key), pk,
                        new CosmosItemRequestOptions().setIfMatchETag(v.version().value()));
            };
            return new Version(response.getETag());
        } catch (CosmosException e) {
            if (e.getStatusCode() == 409 || e.getStatusCode() == 412
                    || (e.getStatusCode() == 404 && condition instanceof WriteCondition.IfVersion)) {
                throw new PreconditionFailedException("Condition failed for " + key, e, AzureProvider.ID, String.valueOf(e.getStatusCode()));
            }
            throw e;
        }
    }

    @Override
    public Item update(Key key, DocumentUpdate update, Optional<Version> ifVersion, Instant expiresAt) {
        try {
            return patch(key, update, update.remove(), ifVersion, expiresAt);
        } catch (CosmosException e) {
            if (e.getStatusCode() == 400 && !update.remove().isEmpty()) {
                // Cosmos rejects removing a field that isn't there: drop those removals and retry on this version.
                Item current = get(key).orElseThrow(() -> new NotFoundException("No item " + key, e, AzureProvider.ID, "404"));
                if (ifVersion.isPresent() && !ifVersion.get().equals(current.version())) {
                    throw new PreconditionFailedException("Item " + key + " changed", e, AzureProvider.ID, "412");
                }
                Set<String> present = new HashSet<>(update.remove());
                present.retainAll(current.document().fieldNames());
                try {
                    return patch(key, update, present, Optional.of(current.version()), expiresAt);
                } catch (CosmosException retry) {
                    throw conditionFailure(key, retry);
                }
            }
            throw conditionFailure(key, e);
        }
    }

    private RuntimeException conditionFailure(Key key, CosmosException e) {
        return switch (e.getStatusCode()) {
            case 404 -> new NotFoundException("No item " + key, e, AzureProvider.ID, "404");
            case 412 -> new PreconditionFailedException("Item " + key + " changed", e, AzureProvider.ID, "412");
            default -> e;
        };
    }

    private Item patch(Key key, DocumentUpdate update, Set<String> removes, Optional<Version> ifVersion, Instant expiresAt) {
        CosmosPatchOperations operations = CosmosPatchOperations.create();
        update.set().forEach((field, value) -> operations.set(path(field), CosmosValues.toJson(value)));
        removes.forEach(field -> operations.remove(path(field)));
        update.increment().forEach((field, amount) -> {
            if (amount instanceof Double d) {
                operations.increment(path(field), d);
            } else {
                operations.increment(path(field), amount.longValue());
            }
        });
        if (expiresAt != null) {
            operations.set("/ttl", Math.max(1, Duration.between(Instant.now(), expiresAt).toSeconds() + 1));
            operations.set("/" + EXPIRES, expiresAt.toEpochMilli());
        }
        var options = new CosmosPatchItemRequestOptions();
        ifVersion.ifPresent(v -> options.setIfMatchETag(v.value()));
        var response = container.patchItem(id(key), new PartitionKey(key.partition()), operations, options, ObjectNode.class);
        return toItem(response.getItem(), response.getETag());
    }

    /** A JSON pointer to a top-level field. */
    private static String path(String field) {
        return "/" + field.replace("~", "~0").replace("/", "~1");
    }

    @Override
    public boolean delete(Key key, Optional<Version> ifVersion) {
        var options = new CosmosItemRequestOptions();
        ifVersion.ifPresent(v -> options.setIfMatchETag(v.value()));
        try {
            container.deleteItem(id(key), new PartitionKey(key.partition()), options);
            return true;
        } catch (CosmosException e) {
            if (e.getStatusCode() == 404 && ifVersion.isEmpty()) {
                return false;
            }
            if (e.getStatusCode() == 404 || e.getStatusCode() == 412) {
                throw new PreconditionFailedException("Item " + key + " is not at version " + ifVersion.orElseThrow(),
                        e, AzureProvider.ID, String.valueOf(e.getStatusCode()));
            }
            throw e;
        }
    }

    @Override
    public Map<Key, Item> getAll(List<Key> keys) {
        List<CosmosItemIdentity> identities = keys.stream()
                .map(k -> new CosmosItemIdentity(new PartitionKey(k.partition()), id(k))).toList();
        Map<Key, Item> found = new LinkedHashMap<>();
        for (ObjectNode node : container.readMany(identities, ObjectNode.class).getResults()) {
            Item item = toItem(node, null);
            found.put(item.key(), item);
        }
        return found;
    }

    @Override
    public int maxBatchGet() {
        return 100;
    }

    /** Cosmos batches only within one partition, so items are upserted concurrently instead. */
    @Override
    public void putAll(Map<Key, Document> items) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> writes = new ArrayList<>();
            items.forEach((key, document) -> writes.add(executor.submit(() ->
                    container.upsertItem(toNode(key, document, null), new PartitionKey(key.partition()),
                            new CosmosItemRequestOptions()))));
            for (Future<?> write : writes) {
                try {
                    write.get();
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof RuntimeException runtime) {
                        throw runtime;
                    }
                    throw new UnknownCloudException("Cosmos upsert failed", e.getCause(), AzureProvider.ID, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new UnknownCloudException("Interrupted writing to Cosmos", e, AzureProvider.ID, null);
                }
            }
        }
    }

    @Override
    public int maxBatchWrite() {
        return 50;
    }

    @Override
    public QueryPage query(Query query) {
        String sk = "c[\"" + table.sortKey().orElseThrow() + "\"]";
        String pk = "c[\"" + table.partitionKey() + "\"]";
        StringBuilder sql = new StringBuilder("SELECT * FROM c WHERE " + pk + " = @pk");
        List<SqlParameter> parameters = new ArrayList<>(List.of(new SqlParameter("@pk", query.partitionKey())));
        query.condition().ifPresent(c -> {
            switch (c) {
                case SortCondition.BeginsWith b -> {
                    sql.append(" AND STARTSWITH(").append(sk).append(", @a)");
                    parameters.add(new SqlParameter("@a", b.prefix()));
                }
                case SortCondition.Between b -> {
                    sql.append(" AND ").append(sk).append(" >= @a AND ").append(sk).append(" <= @b");
                    parameters.add(new SqlParameter("@a", b.from()));
                    parameters.add(new SqlParameter("@b", b.to()));
                }
                case SortCondition.Compare cmp -> {
                    sql.append(" AND ").append(sk).append(switch (cmp.operator()) {
                        case GT -> " > ";
                        case GE -> " >= ";
                        case LT -> " < ";
                        case LE -> " <= ";
                    }).append("@a");
                    parameters.add(new SqlParameter("@a", cmp.value()));
                }
            }
        });
        sql.append(" ORDER BY ").append(sk).append(query.descending() ? " DESC" : " ASC");
        var options = new CosmosQueryRequestOptions().setPartitionKey(new PartitionKey(query.partitionKey()));
        var pages = container.queryItems(new SqlQuerySpec(sql.toString(), parameters), options, ObjectNode.class)
                .iterableByPage(query.pageToken().orElse(null), query.limit()).iterator();
        if (!pages.hasNext()) {
            return new QueryPage(List.of(), Optional.empty());
        }
        var page = pages.next();
        List<Item> items = page.getResults().stream().map(n -> toItem(n, null)).toList();
        return new QueryPage(items, Optional.ofNullable(page.getContinuationToken()));
    }

    /** A cross-partition query on the index's fields; Cosmos' default indexing covers it. */
    @Override
    public QueryPage queryIndex(Index index, IndexQuery query) {
        String attribute = "c[\"" + index.partitionAttribute() + "\"]";
        StringBuilder sql = new StringBuilder("SELECT * FROM c WHERE " + attribute + " = @v");
        List<SqlParameter> parameters = new ArrayList<>(List.of(new SqlParameter("@v", CosmosValues.toJson(query.value()))));
        if (index.sortAttribute().isPresent()) {
            String sort = "c[\"" + index.sortAttribute().get() + "\"]";
            sql.append(" AND IS_STRING(").append(sort).append(")");
            query.condition().ifPresent(c -> sql.append(sortClause(sort, c, parameters)));
            sql.append(" ORDER BY ").append(sort).append(query.descending() ? " DESC" : " ASC");
        }
        var pages = container.queryItems(new SqlQuerySpec(sql.toString(), parameters), new CosmosQueryRequestOptions(),
                ObjectNode.class).iterableByPage(query.pageToken().orElse(null), query.limit()).iterator();
        if (!pages.hasNext()) {
            return new QueryPage(List.of(), Optional.empty());
        }
        var page = pages.next();
        return new QueryPage(page.getResults().stream().map(n -> toItem(n, null)).toList(),
                Optional.ofNullable(page.getContinuationToken()));
    }

    private static String sortClause(String field, SortCondition condition, List<SqlParameter> parameters) {
        return switch (condition) {
            case SortCondition.BeginsWith b -> {
                parameters.add(new SqlParameter("@a", b.prefix()));
                yield " AND STARTSWITH(" + field + ", @a)";
            }
            case SortCondition.Between b -> {
                parameters.add(new SqlParameter("@a", b.from()));
                parameters.add(new SqlParameter("@b", b.to()));
                yield " AND " + field + " >= @a AND " + field + " <= @b";
            }
            case SortCondition.Compare c -> {
                parameters.add(new SqlParameter("@a", c.value()));
                yield " AND " + field + switch (c.operator()) {
                    case GT -> " > ";
                    case GE -> " >= ";
                    case LT -> " < ";
                    case LE -> " <= ";
                } + "@a";
            }
        };
    }

    @Override
    public CloudException translate(Throwable error) {
        if (error instanceof CloudException c) {
            return c;
        }
        if (error instanceof CosmosException e) {
            String code = String.valueOf(e.getStatusCode());
            String message = e.getShortMessage();
            return switch (e.getStatusCode()) {
                case 404 -> new NotFoundException(message, e, AzureProvider.ID, code);
                case 409, 412 -> new PreconditionFailedException(message, e, AzureProvider.ID, code);
                case 401, 403 -> new AccessDeniedException(message, e, AzureProvider.ID, code);
                case 400, 413 -> new InvalidArgumentException(message, e, AzureProvider.ID, code);
                case 429 -> new ThrottledException(message, e, AzureProvider.ID, code, e.getRetryAfterDuration());
                default -> e.getStatusCode() >= 500 || e.getStatusCode() == 408
                        ? new UnavailableException(message, e, AzureProvider.ID, code)
                        : new UnknownCloudException(message, e, AzureProvider.ID, code);
            };
        }
        return new UnknownCloudException(error.getMessage(), error, AzureProvider.ID, null);
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        if (nativeType.isInstance(container)) {
            return nativeType.cast(container);
        }
        throw new IllegalArgumentException("No native client of type " + nativeType.getName());
    }
}
