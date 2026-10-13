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
package dev.halyx.aws.dynamodb;

import dev.halyx.aws.AwsErrors;
import dev.halyx.aws.AwsProvider;
import dev.halyx.error.CloudException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.PreconditionFailedException;
import dev.halyx.error.ThrottledException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.json.Json;
import dev.halyx.nosql.Document;
import dev.halyx.nosql.DocumentUpdate;
import dev.halyx.nosql.Index;
import dev.halyx.nosql.IndexQuery;
import dev.halyx.nosql.Item;
import dev.halyx.nosql.Key;
import dev.halyx.nosql.Query;
import dev.halyx.nosql.QueryPage;
import dev.halyx.nosql.SortCondition;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.Version;
import dev.halyx.nosql.spi.DocumentStoreDriver;
import dev.halyx.nosql.spi.TransactionWrite;
import dev.halyx.nosql.spi.WriteCondition;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A DynamoDB table with string partition (and optional sort) keys. Halyx keeps each item's version in
 * {@value #VERSION} and its expiry, in epoch seconds, in the TTL attribute ({@value #DEFAULT_TTL_ATTRIBUTE}
 * unless {@code aws.dynamodb.ttl-attribute} says otherwise): enable DynamoDB TTL on that attribute to have
 * expired items deleted. Reads and queries are strongly consistent.
 */
public final class DynamoDocumentStoreDriver implements DocumentStoreDriver {

    static final String VERSION = "halyx_version";
    static final String DEFAULT_TTL_ATTRIBUTE = "halyx_expires";
    public static final String TTL_ATTRIBUTE = "aws.dynamodb.ttl-attribute";

    private final DynamoDbClient dynamo;
    private final TableConfig table;
    private final String ttl;

    public DynamoDocumentStoreDriver(DynamoDbClient dynamo, TableConfig table) {
        this.dynamo = dynamo;
        this.table = table;
        this.ttl = table.property(TTL_ATTRIBUTE).orElse(DEFAULT_TTL_ATTRIBUTE);
    }

    @Override
    public String provider() {
        return AwsProvider.ID;
    }

    @Override
    public TableConfig table() {
        return table;
    }

    @Override
    public Set<String> reservedFields() {
        return Set.of(VERSION, ttl);
    }

    private Set<String> internal() {
        Set<String> names = new HashSet<>(reservedFields());
        names.add(table.partitionKey());
        table.sortKey().ifPresent(names::add);
        return names;
    }

    private Map<String, AttributeValue> keyAttributes(Key key) {
        Map<String, AttributeValue> attributes = new LinkedHashMap<>();
        attributes.put(table.partitionKey(), AttributeValue.fromS(key.partition()));
        table.sortKey().ifPresent(sk -> attributes.put(sk, AttributeValue.fromS(key.sort().orElseThrow())));
        return attributes;
    }

    private Key keyOf(Map<String, AttributeValue> attributes) {
        return table.sortKey().map(sk -> Key.of(attributes.get(table.partitionKey()).s(), attributes.get(sk).s()))
                .orElseGet(() -> Key.of(attributes.get(table.partitionKey()).s()));
    }

    private Item toItem(Map<String, AttributeValue> attributes) {
        Key key = keyOf(attributes);
        AttributeValue version = attributes.get(VERSION);
        AttributeValue expires = attributes.get(ttl);
        return new Item(key, DynamoValues.fromAttributes(attributes, internal()),
                new Version(version == null ? "0" : version.s()),
                Optional.ofNullable(expires).map(e -> Instant.ofEpochSecond(Long.parseLong(e.n()))));
    }

    @Override
    public Optional<Item> get(Key key) {
        var response = dynamo.getItem(b -> b.tableName(table.name()).key(keyAttributes(key)).consistentRead(true));
        return response.hasItem() && !response.item().isEmpty() ? Optional.of(toItem(response.item())) : Optional.empty();
    }

    @Override
    public Version put(Key key, Document document, WriteCondition condition, Instant expiresAt) {
        Map<String, AttributeValue> item = new LinkedHashMap<>(DynamoValues.toAttributes(document, Set.of()));
        item.putAll(keyAttributes(key));
        String version = UUID.randomUUID().toString();
        item.put(VERSION, AttributeValue.fromS(version));
        if (expiresAt != null) {
            item.put(ttl, AttributeValue.fromN(String.valueOf(expiresAt.getEpochSecond())));
        }
        try {
            dynamo.putItem(b -> {
                b.tableName(table.name()).item(item);
                switch (condition) {
                    case WriteCondition.None ignored -> { }
                    // An expired item DynamoDB hasn't swept yet counts as absent.
                    case WriteCondition.NotExists ignored -> b.conditionExpression("attribute_not_exists(#pk) OR #ttl < :now")
                            .expressionAttributeNames(Map.of("#pk", table.partitionKey(), "#ttl", ttl))
                            .expressionAttributeValues(Map.of(":now", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond()))));
                    case WriteCondition.IfVersion v -> b.conditionExpression("#v = :v")
                            .expressionAttributeNames(Map.of("#v", VERSION))
                            .expressionAttributeValues(Map.of(":v", AttributeValue.fromS(v.version().value())));
                }
            });
        } catch (ConditionalCheckFailedException e) {
            throw new PreconditionFailedException("Condition failed for " + key, e, AwsProvider.ID, e.awsErrorDetails().errorCode());
        }
        return new Version(version);
    }

    /** An UpdateItem expression for {@code update}, conditional on the item existing (and on its version). */
    private record UpdateSpec(String expression, String condition, Map<String, String> names,
                              Map<String, AttributeValue> values) {
    }

    private UpdateSpec updateSpec(DocumentUpdate update, Optional<Version> ifVersion, Instant expiresAt) {
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        List<String> sets = new ArrayList<>();
        List<String> removes = new ArrayList<>();
        List<String> adds = new ArrayList<>();
        int i = 0;
        for (var entry : update.set().entrySet()) {
            names.put("#f" + i, entry.getKey());
            values.put(":f" + i, DynamoValues.toAttribute(entry.getValue()));
            sets.add("#f" + i + " = :f" + i);
            i++;
        }
        for (String field : update.remove()) {
            names.put("#f" + i, field);
            removes.add("#f" + i);
            i++;
        }
        for (var entry : update.increment().entrySet()) {
            names.put("#f" + i, entry.getKey());
            values.put(":f" + i, DynamoValues.toAttribute(entry.getValue() instanceof Double d ? d : entry.getValue().longValue()));
            adds.add("#f" + i + " :f" + i);
            i++;
        }
        names.put("#v", VERSION);
        values.put(":newv", AttributeValue.fromS(UUID.randomUUID().toString()));
        sets.add("#v = :newv");
        if (expiresAt != null) {
            names.put("#ttl", ttl);
            values.put(":ttl", AttributeValue.fromN(String.valueOf(expiresAt.getEpochSecond())));
            sets.add("#ttl = :ttl");
        }
        StringBuilder expression = new StringBuilder("SET ").append(String.join(", ", sets));
        if (!removes.isEmpty()) {
            expression.append(" REMOVE ").append(String.join(", ", removes));
        }
        if (!adds.isEmpty()) {
            expression.append(" ADD ").append(String.join(", ", adds));
        }
        names.put("#pk", table.partitionKey());
        String condition = "attribute_exists(#pk)";
        if (ifVersion.isPresent()) {
            values.put(":oldv", AttributeValue.fromS(ifVersion.get().value()));
            condition += " AND #v = :oldv";
        }
        return new UpdateSpec(expression.toString(), condition, names, values);
    }

    @Override
    public Item update(Key key, DocumentUpdate update, Optional<Version> ifVersion, Instant expiresAt) {
        UpdateSpec spec = updateSpec(update, ifVersion, expiresAt);
        try {
            var response = dynamo.updateItem(b -> b.tableName(table.name()).key(keyAttributes(key))
                    .updateExpression(spec.expression()).conditionExpression(spec.condition())
                    .expressionAttributeNames(spec.names()).expressionAttributeValues(spec.values())
                    .returnValues(ReturnValue.ALL_NEW));
            return toItem(response.attributes());
        } catch (ConditionalCheckFailedException e) {
            if (get(key).isEmpty()) {
                throw new NotFoundException("No item " + key, e, AwsProvider.ID, e.awsErrorDetails().errorCode());
            }
            throw new PreconditionFailedException("Item " + key + " changed", e, AwsProvider.ID, e.awsErrorDetails().errorCode());
        }
    }

    @Override
    public boolean delete(Key key, Optional<Version> ifVersion) {
        try {
            var response = dynamo.deleteItem(b -> {
                b.tableName(table.name()).key(keyAttributes(key)).returnValues(ReturnValue.ALL_OLD);
                ifVersion.ifPresent(v -> b.conditionExpression("#v = :v")
                        .expressionAttributeNames(Map.of("#v", VERSION))
                        .expressionAttributeValues(Map.of(":v", AttributeValue.fromS(v.value()))));
            });
            return response.hasAttributes() && !response.attributes().isEmpty();
        } catch (ConditionalCheckFailedException e) {
            throw new PreconditionFailedException("Item " + key + " changed", e, AwsProvider.ID, e.awsErrorDetails().errorCode());
        }
    }

    /**
     * One BatchGetItem. Keys DynamoDB leaves unprocessed (it does under throttling) fail the call with
     * {@link ThrottledException}, which is retryable; nothing here waits and resends them.
     */
    @Override
    public Map<Key, Item> getAll(List<Key> keys) {
        Map<String, KeysAndAttributes> request = Map.of(table.name(), KeysAndAttributes.builder()
                .keys(keys.stream().map(this::keyAttributes).toList()).consistentRead(true).build());
        var response = dynamo.batchGetItem(b -> b.requestItems(request));
        KeysAndAttributes unprocessed = response.hasUnprocessedKeys() ? response.unprocessedKeys().get(table.name()) : null;
        if (unprocessed != null && !unprocessed.keys().isEmpty()) {
            throw throttled("read", unprocessed.keys().stream().map(this::keyOf).toList());
        }
        Map<Key, Item> found = new LinkedHashMap<>();
        response.responses().getOrDefault(table.name(), List.of()).forEach(a -> {
            Item item = toItem(a);
            found.put(item.key(), item);
        });
        return found;
    }

    @Override
    public int maxBatchGet() {
        return 100;
    }

    /**
     * One BatchWriteItem. Items DynamoDB leaves unprocessed (it does under throttling) fail the call with
     * {@link ThrottledException}, which is retryable; the processed ones stay written, and writing them
     * again replaces them with the same content. Nothing here waits and resends.
     */
    @Override
    public void putAll(Map<Key, Document> items) {
        List<WriteRequest> writes = new ArrayList<>();
        items.forEach((key, document) -> {
            Map<String, AttributeValue> item = new LinkedHashMap<>(DynamoValues.toAttributes(document, Set.of()));
            item.putAll(keyAttributes(key));
            item.put(VERSION, AttributeValue.fromS(UUID.randomUUID().toString()));
            writes.add(WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build());
        });
        var response = dynamo.batchWriteItem(b -> b.requestItems(Map.of(table.name(), writes)));
        List<WriteRequest> unprocessed = response.hasUnprocessedItems()
                ? response.unprocessedItems().getOrDefault(table.name(), List.of()) : List.of();
        if (!unprocessed.isEmpty()) {
            throw throttled("written", unprocessed.stream().map(w -> keyOf(w.putRequest().item())).toList());
        }
    }

    private ThrottledException throttled(String what, List<Key> keys) {
        return new ThrottledException("DynamoDB is throttling table " + table.name() + "; " + keys.size()
                + " item(s) were not " + what + ": " + keys, null, AwsProvider.ID, "UnprocessedItems");
    }

    @Override
    public int maxBatchWrite() {
        return 25;
    }

    @Override
    public QueryPage query(Query query) {
        String sk = table.sortKey().orElseThrow();
        // DynamoDB rejects names the expression doesn't use, so #sk only joins with a sort condition.
        Map<String, String> names = new HashMap<>(Map.of("#pk", table.partitionKey()));
        query.condition().ifPresent(c -> names.put("#sk", sk));
        Map<String, AttributeValue> values = new HashMap<>(Map.of(":pk", AttributeValue.fromS(query.partitionKey())));
        StringBuilder condition = new StringBuilder("#pk = :pk");
        query.condition().ifPresent(c -> {
            switch (c) {
                case SortCondition.BeginsWith b -> {
                    condition.append(" AND begins_with(#sk, :a)");
                    values.put(":a", AttributeValue.fromS(b.prefix()));
                }
                case SortCondition.Between b -> {
                    condition.append(" AND #sk BETWEEN :a AND :b");
                    values.put(":a", AttributeValue.fromS(b.from()));
                    values.put(":b", AttributeValue.fromS(b.to()));
                }
                case SortCondition.Compare cmp -> {
                    condition.append(" AND #sk ").append(switch (cmp.operator()) {
                        case GT -> ">";
                        case GE -> ">=";
                        case LT -> "<";
                        case LE -> "<=";
                    }).append(" :a");
                    values.put(":a", AttributeValue.fromS(cmp.value()));
                }
            }
        });
        var response = dynamo.query(b -> {
            b.tableName(table.name()).keyConditionExpression(condition.toString())
                    .expressionAttributeNames(names).expressionAttributeValues(values)
                    .scanIndexForward(!query.descending()).limit(query.limit()).consistentRead(true);
            query.pageToken().ifPresent(token -> {
                Map<String, AttributeValue> start = new HashMap<>();
                start.put(table.partitionKey(), AttributeValue.fromS(query.partitionKey()));
                start.put(sk, AttributeValue.fromS(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)));
                b.exclusiveStartKey(start);
            });
        });
        List<Item> items = response.items().stream().map(this::toItem).toList();
        Optional<String> next = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                ? Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString(
                        response.lastEvaluatedKey().get(sk).s().getBytes(StandardCharsets.UTF_8)))
                : Optional.empty();
        return new QueryPage(items, next);
    }

    /** TransactWriteItems; a cancelled transaction for a failed condition is a PreconditionFailedException. */
    @Override
    public Map<Key, Version> transact(List<TransactionWrite> operations) {
        Map<Key, Version> versions = new LinkedHashMap<>();
        List<TransactWriteItem> items = new ArrayList<>();
        for (TransactionWrite op : operations) {
            switch (op) {
                case TransactionWrite.Put put -> {
                    Map<String, AttributeValue> item = new LinkedHashMap<>(DynamoValues.toAttributes(put.document(), Set.of()));
                    item.putAll(keyAttributes(put.key()));
                    String version = UUID.randomUUID().toString();
                    item.put(VERSION, AttributeValue.fromS(version));
                    if (put.expiresAt() != null) {
                        item.put(ttl, AttributeValue.fromN(String.valueOf(put.expiresAt().getEpochSecond())));
                    }
                    versions.put(put.key(), new Version(version));
                    items.add(TransactWriteItem.builder().put(p -> {
                        p.tableName(table.name()).item(item);
                        switch (put.condition()) {
                            case WriteCondition.None ignored -> { }
                            case WriteCondition.NotExists ignored -> p.conditionExpression("attribute_not_exists(#pk) OR #ttl < :now")
                                    .expressionAttributeNames(Map.of("#pk", table.partitionKey(), "#ttl", ttl))
                                    .expressionAttributeValues(Map.of(":now", AttributeValue.fromN(String.valueOf(Instant.now().getEpochSecond()))));
                            case WriteCondition.IfVersion v -> p.conditionExpression("#v = :v")
                                    .expressionAttributeNames(Map.of("#v", VERSION))
                                    .expressionAttributeValues(Map.of(":v", AttributeValue.fromS(v.version().value())));
                        }
                    }).build());
                }
                case TransactionWrite.Update update -> {
                    UpdateSpec spec = updateSpec(update.update(), update.ifVersion(), update.expiresAt());
                    versions.put(update.key(), new Version(spec.values().get(":newv").s()));
                    items.add(TransactWriteItem.builder().update(u -> u.tableName(table.name()).key(keyAttributes(update.key()))
                            .updateExpression(spec.expression()).conditionExpression(spec.condition())
                            .expressionAttributeNames(spec.names()).expressionAttributeValues(spec.values())).build());
                }
                case TransactionWrite.Delete delete -> items.add(TransactWriteItem.builder().delete(d -> {
                    d.tableName(table.name()).key(keyAttributes(delete.key()));
                    delete.ifVersion().ifPresent(v -> d.conditionExpression("#v = :v")
                            .expressionAttributeNames(Map.of("#v", VERSION))
                            .expressionAttributeValues(Map.of(":v", AttributeValue.fromS(v.value()))));
                }).build());
                case TransactionWrite.Check check -> items.add(TransactWriteItem.builder().conditionCheck(c -> c
                        .tableName(table.name()).key(keyAttributes(check.key())).conditionExpression("#v = :v")
                        .expressionAttributeNames(Map.of("#v", VERSION))
                        .expressionAttributeValues(Map.of(":v", AttributeValue.fromS(check.version().value())))).build());
            }
        }
        try {
            dynamo.transactWriteItems(b -> b.transactItems(items));
        } catch (TransactionCanceledException e) {
            boolean conditionFailed = e.hasCancellationReasons() && e.cancellationReasons().stream()
                    .anyMatch(r -> "ConditionalCheckFailed".equals(r.code()));
            if (conditionFailed) {
                throw new PreconditionFailedException("Transaction cancelled: a condition failed", e, AwsProvider.ID,
                        "ConditionalCheckFailed");
            }
            throw e;
        }
        return versions;
    }

    /** A global secondary index of the index's name; GSI reads are eventually consistent. */
    @Override
    public QueryPage queryIndex(Index index, IndexQuery query) {
        Map<String, String> names = new HashMap<>(Map.of("#a", index.partitionAttribute()));
        Map<String, AttributeValue> values = new HashMap<>(Map.of(":v", DynamoValues.toAttribute(query.value())));
        StringBuilder condition = new StringBuilder("#a = :v");
        query.condition().ifPresent(c -> {
            names.put("#s", index.sortAttribute().orElseThrow());
            condition.append(sortCondition("#s", c, values));
        });
        var response = dynamo.query(b -> {
            b.tableName(table.name()).indexName(index.name()).keyConditionExpression(condition.toString())
                    .expressionAttributeNames(names).expressionAttributeValues(values)
                    .scanIndexForward(!query.descending()).limit(query.limit());
            query.pageToken().ifPresent(token -> b.exclusiveStartKey(decodeKey(token)));
        });
        List<Item> items = response.items().stream().map(this::toItem).toList();
        Optional<String> next = response.hasLastEvaluatedKey() && !response.lastEvaluatedKey().isEmpty()
                ? Optional.of(encodeKey(response.lastEvaluatedKey()))
                : Optional.empty();
        return new QueryPage(items, next);
    }

    /** A sort-key condition clause on {@code name}, with its values added to {@code values}. */
    private static String sortCondition(String name, SortCondition condition, Map<String, AttributeValue> values) {
        return switch (condition) {
            case SortCondition.BeginsWith b -> {
                values.put(":a", AttributeValue.fromS(b.prefix()));
                yield " AND begins_with(" + name + ", :a)";
            }
            case SortCondition.Between b -> {
                values.put(":a", AttributeValue.fromS(b.from()));
                values.put(":b", AttributeValue.fromS(b.to()));
                yield " AND " + name + " BETWEEN :a AND :b";
            }
            case SortCondition.Compare c -> {
                values.put(":a", AttributeValue.fromS(c.value()));
                yield " AND " + name + switch (c.operator()) {
                    case GT -> " > ";
                    case GE -> " >= ";
                    case LT -> " < ";
                    case LE -> " <= ";
                } + ":a";
            }
        };
    }

    /** An index query's last evaluated key holds table and index keys: kept as JSON of their S or N values. */
    private static String encodeKey(Map<String, AttributeValue> key) {
        Map<String, Object> json = new LinkedHashMap<>();
        key.forEach((name, value) -> json.put(name, value.s() != null ? Map.of("S", value.s()) : Map.of("N", value.n())));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Json.write(json).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, AttributeValue> decodeKey(String token) {
        Map<String, Object> json = Json.parseObject(new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8))
                .orElseThrow(() -> new IllegalArgumentException("Not a page token: " + token));
        Map<String, AttributeValue> key = new HashMap<>();
        json.forEach((name, value) -> {
            Map<String, Object> typed = (Map<String, Object>) value;
            key.put(name, typed.containsKey("S") ? AttributeValue.fromS(String.valueOf(typed.get("S")))
                    : AttributeValue.fromN(String.valueOf(typed.get("N"))));
        });
        return key;
    }

    @Override
    public CloudException translate(Throwable error) {
        if (error instanceof CloudException c) {
            return c;
        }
        return AwsErrors.translate(error).orElseGet(() -> new UnknownCloudException(error.getMessage(), error, AwsProvider.ID, null));
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        if (nativeType.isInstance(dynamo)) {
            return nativeType.cast(dynamo);
        }
        throw new IllegalArgumentException("No native client of type " + nativeType.getName());
    }
}
