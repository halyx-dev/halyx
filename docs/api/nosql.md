---
title: NoSQL documents
parent: API guide
nav_order: 2
---

# NoSQL documents: `DocumentStore`
{: .no_toc }

One API for **Amazon DynamoDB**, **Azure Cosmos DB for NoSQL** and **Google Cloud Firestore** (native
mode), plus an in-memory store. Items are keyed by a partition key and an optional sort key, and hold a
portable `Document`.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Tables

A `TableConfig` names the table (DynamoDB table, Cosmos container or Firestore collection) and its keys:

```java
DocumentStore orders = cirrus.provider("aws").documentStore(TableConfig.builder("orders")
        .sortKey("sk")                                   // omit for tables keyed by partition only
        .index("by-status", "status", "placedAt")        // a secondary index: partition and sort attribute
        .build());

DocumentStore customers = cirrus.provider("gcp").documentStore(TableConfig.of("customers"));
```

The key attributes are called `pk` and `sk` by default (`partitionKey(...)` and `sortKey(...)` rename
them). Tables are infrastructure: create them with your usual tooling, with the key attributes named as
in the `TableConfig`.

## Items and documents

```java
Key key = Key.of("acme", "2026-10-11#1");          // partition, sort
Document order = Document.builder()
        .put("status", "PAID")
        .put("total", 120)
        .put("placedAt", "2026-10-11T09:30:00Z")
        .put("lines", List.of(Document.builder().put("sku", "A-1").put("qty", 2).build()))
        .build();

Item item = orders.put(key, order);                 // create or replace
Optional<Item> found = orders.get(key);
found.ifPresent(i -> {
    Document d = i.document();
    String status = d.getString("status").orElseThrow();
    long total = d.getLong("total").orElse(0L);
    Version version = i.version();                  // for optimistic concurrency
});
boolean deleted = orders.delete(key);               // false if it didn't exist
```

**Values:** strings, whole numbers, doubles, decimals (`BigDecimal`), booleans, binary, null, lists and
nested documents. Read them back with `getString`, `getLong`, `getDouble`, `getDecimal`, `getBoolean`,
`getBinary`, `getDocument` and `getList`.

### Records

`RecordMapper` maps Java records to documents and back, including enums, `Instant`, `LocalDate`, `UUID`,
`Optional`, lists and maps:

```java
record Order(String status, BigDecimal total, Instant placedAt, List<Line> lines) {}

RecordMapper<Order> mapper = RecordMapper.of(Order.class);
orders.put(key, mapper.toDocument(order));
Order loaded = mapper.fromDocument(orders.get(key).orElseThrow().document());
```

## Partial updates

`update` changes fields atomically, without reading the item first:

```java
Item updated = orders.update(key, DocumentUpdate.builder()
        .set("status", "SHIPPED")
        .increment("total", 5)
        .remove("draftNotes")
        .build());
```

Concurrent increments are never lost. Updating a missing item fails with `NotFoundException`.

## Optimistic concurrency and conditions

Every item has a `Version`. `WriteOptions` turn lost updates into `PreconditionFailedException`:

```java
Item current = orders.get(key).orElseThrow();
try {
    orders.update(key, DocumentUpdate.builder().set("status", "PAID").build(),
            WriteOptions.ifVersion(current.version()));     // fails if someone else wrote first
} catch (PreconditionFailedException e) {
    // reread and retry, or report a conflict
}

orders.put(Key.of("acme", "2026-10-11#2"), order, WriteOptions.ifNotExists());   // create-only
orders.delete(key, current.version());                                         // conditional delete
```

## Expiry

```java
orders.put(Key.of("session", token), session, WriteOptions.builder().ttl(Duration.ofHours(1)).build());
```

Expired items are never returned, even before the store deletes them, and they don't block
`ifNotExists`. Enable the store's own expiry so they are eventually removed (see
[provider details](#provider-details)).

## Queries

Queries read one partition, ordered by sort key, optionally narrowed by a sort-key condition:

```java
// Lazily stream every match, following page tokens for you
orders.queryAll(Query.builder("acme")
                .where(SortCondition.beginsWith("2026-10"))
                .descending()
                .build())
        .map(i -> mapper.fromDocument(i.document()))
        .forEach(this::show);

// Or page by hand, for example behind an API
QueryPage page = orders.query(Query.builder("acme").limit(50).pageToken(token).build());
page.items();
page.nextPageToken();   // Optional: empty on the last page
```

Sort conditions: `equalTo`, `beginsWith`, `between`, `greaterThan`, `greaterThanOrEqual`, `lessThan` and
`lessThanOrEqual`. `Query.partition("acme")` reads a whole partition.

## Batches

```java
Map<Key, Item> items = orders.getAll(List.of(key1, key2, key3));   // missing keys are left out
orders.putAll(Map.of(key1, doc1, key2, doc2));
```

Batches are split into each provider's limits. If the store throttles part of a batch (DynamoDB returns
unprocessed items), the call throws the retryable `ThrottledException` naming the items, instead of
waiting and resending. Retrying `putAll` is safe, because it rewrites the same content.

## Secondary indexes

Declare an index on the table, then query across partitions by its attribute:

```java
orders.queryIndexAll(IndexQuery.builder("by-status")
                .equalTo("PAID")
                .where(SortCondition.greaterThan("2026-10-01"))   // on the index's sort attribute
                .descending()
                .build())
        .forEach(this::ship);
```

Results are ordered and narrowed by the index's sort attribute. Items without the indexed attribute
aren't in the index.

## Transactions

Up to 100 operations on one partition, applied atomically:

```java
Map<Key, Version> versions = orders.transact(Transaction.builder()
        .put(Key.of("acme", "order#2"), orderDoc, WriteOptions.ifNotExists())
        .put(Key.of("acme", "order#2#line#1"), lineDoc)
        .update(Key.of("acme", "counter"), DocumentUpdate.builder().increment("orders", 1).build())
        .delete(Key.of("acme", "draft"))
        .check(Key.of("acme", "account"), accountVersion)   // must still be at this version
        .build());
```

If any condition fails, nothing is applied, and the call throws `PreconditionFailedException`. It
returns the new versions of the written items.

## Provider details

| | DynamoDB | Cosmos DB (NoSQL API) | Firestore (native mode) |
|---|---|---|---|
| Table | table with string `pk` (and `sk`) keys | container partitioned on `/pk` | collection; sorted items in `<table>/<pk>/<table>/<sk>` |
| Version | `cirrus_version` attribute | ETag | update time |
| Expiry | `cirrus_expires` (epoch seconds): enable TTL on it | `ttl` (container default TTL must be on, for example -1) | `cirrus_expires` Timestamp: add a TTL policy |
| Query | key condition on `sk` | SQL within the partition, `ORDER BY sk` | ordered on the `cirrus_sort` field |
| Transaction | `TransactWriteItems` | transactional batch | read-check-write transaction |
| Index | a global secondary index of that name (eventually consistent) | a cross-partition query (default indexing) | a collection-group query: add a composite index on the index fields |
| Decimals | exact | exact (stored as a string wrapper) | stored as doubles |
| Item size | 400 KB | 2 MB | about 1 MiB |
| Reads | strongly consistent | the account's consistency level | strongly consistent |

On Cosmos DB, item ids can't contain `/ \ ? #`; Cirrus escapes them (with `~`), so any key string works.
Keys with those characters are covered by the conformance kit on every provider.

## Native clients

```java
DynamoDbClient dynamo = orders.unwrap(DynamoDbClient.class);          // AWS
CosmosContainer container = orders.unwrap(CosmosContainer.class);     // Azure
Firestore firestore = orders.unwrap(Firestore.class);                 // Google Cloud
```

See [Capabilities and native clients](capabilities-and-unwrap.md).
