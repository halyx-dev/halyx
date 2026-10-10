package org.kamranzafar.cirrus.aws.dynamodb;

import org.kamranzafar.cirrus.aws.AwsErrors;
import org.kamranzafar.cirrus.aws.AwsProvider;
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
import org.kamranzafar.cirrus.nosql.SortCondition;
import org.kamranzafar.cirrus.nosql.TableConfig;
import org.kamranzafar.cirrus.nosql.Version;
import org.kamranzafar.cirrus.nosql.spi.DocumentStoreDriver;
import org.kamranzafar.cirrus.nosql.spi.WriteCondition;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.KeysAndAttributes;
import software.amazon.awssdk.services.dynamodb.model.PutRequest;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
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
 * A DynamoDB table with string partition (and optional sort) keys. Cirrus keeps each item's version in
 * {@value #VERSION} and its expiry, in epoch seconds, in the TTL attribute ({@value #DEFAULT_TTL_ATTRIBUTE}
 * unless {@code aws.dynamodb.ttl-attribute} says otherwise): enable DynamoDB TTL on that attribute to have
 * expired items deleted. Reads and queries are strongly consistent.
 */
public final class DynamoDocumentStoreDriver implements DocumentStoreDriver {

    static final String VERSION = "cirrus_version";
    static final String DEFAULT_TTL_ATTRIBUTE = "cirrus_expires";
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

    private Item toItem(Map<String, AttributeValue> attributes) {
        Key key = table.sortKey().map(sk -> Key.of(attributes.get(table.partitionKey()).s(), attributes.get(sk).s()))
                .orElseGet(() -> Key.of(attributes.get(table.partitionKey()).s()));
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

    @Override
    public Item update(Key key, DocumentUpdate update, Optional<Version> ifVersion, Instant expiresAt) {
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
        String conditionExpression = condition;
        try {
            var response = dynamo.updateItem(b -> b.tableName(table.name()).key(keyAttributes(key))
                    .updateExpression(expression.toString()).conditionExpression(conditionExpression)
                    .expressionAttributeNames(names).expressionAttributeValues(values)
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

    @Override
    public Map<Key, Item> getAll(List<Key> keys) {
        Map<Key, Item> found = new LinkedHashMap<>();
        Map<String, KeysAndAttributes> request = Map.of(table.name(), KeysAndAttributes.builder()
                .keys(keys.stream().map(this::keyAttributes).toList()).consistentRead(true).build());
        for (int attempt = 0; !request.isEmpty(); attempt++) {
            var requestNow = request;
            var response = dynamo.batchGetItem(b -> b.requestItems(requestNow));
            response.responses().getOrDefault(table.name(), List.of()).forEach(a -> {
                Item item = toItem(a);
                found.put(item.key(), item);
            });
            request = response.hasUnprocessedKeys() ? response.unprocessedKeys() : Map.of();
            backoff(attempt, !request.isEmpty());
        }
        return found;
    }

    @Override
    public int maxBatchGet() {
        return 100;
    }

    @Override
    public void putAll(Map<Key, Document> items) {
        List<WriteRequest> writes = new ArrayList<>();
        items.forEach((key, document) -> {
            Map<String, AttributeValue> item = new LinkedHashMap<>(DynamoValues.toAttributes(document, Set.of()));
            item.putAll(keyAttributes(key));
            item.put(VERSION, AttributeValue.fromS(UUID.randomUUID().toString()));
            writes.add(WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build());
        });
        Map<String, List<WriteRequest>> request = Map.of(table.name(), writes);
        for (int attempt = 0; !request.isEmpty(); attempt++) {
            var requestNow = request;
            var response = dynamo.batchWriteItem(b -> b.requestItems(requestNow));
            request = response.hasUnprocessedItems() ? response.unprocessedItems() : Map.of();
            request = request.values().stream().allMatch(List::isEmpty) ? Map.of() : request;
            backoff(attempt, !request.isEmpty());
        }
    }

    /** Unprocessed batch entries mean throttling: wait a little longer each time before retrying them. */
    private static void backoff(int attempt, boolean retrying) {
        if (!retrying) {
            return;
        }
        if (attempt >= 8) {
            throw new UnknownCloudException("DynamoDB left batch entries unprocessed after " + attempt + " retries");
        }
        try {
            Thread.sleep(Math.min(2_000, 50L << attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnknownCloudException("Interrupted while retrying a DynamoDB batch");
        }
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
