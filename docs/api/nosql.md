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
DocumentStore orders = halyx.provider("aws").documentStore(TableConfig.builder("orders")
        .sortKey("sk")                                   // omit for tables keyed by partition only
        .index("by-status", "status", "placedAt")        // a secondary index: partition and sort attribute
        .build());

DocumentStore customers = halyx.provider("gcp").documentStore(TableConfig.of("customers"));
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

## Encrypted fields

Fields that hold personal or secret data can be encrypted in the application, with a key from the cloud's key
service, before they reach the database. Name them on the table:

```java
CryptoKey pii = halyx.provider("cloud").keyStore().key("customer-pii");

DocumentStore customers = halyx.provider("cloud").documentStore(TableConfig.builder("customers")
        .encryptedField("email", pii)
        .encryptedField("address", pii)
        .build());

customers.put(Key.of("c-42"), Document.builder()
        .put("name", "Ana")                                  // stored as it is
        .put("email", "ana@example.com")                     // sealed
        .put("address", Map.of("city", "Leeds", "zip", "LS1"))
        .build());
customers.get(Key.of("c-42"));                               // email and address come back as written
```

Everything else stays the same: `get`, `put`, `update`, `getAll`, `putAll`, `query`, `queryIndex`, transactions and
`async()` seal what they write and open what they read. For a key per tenant, choose the key from the item's key:

```java
TableConfig.builder("customers")
        .encryptedField("email", halyx.provider("cloud").keyStore(), key -> "tenant-" + key.partition().split("#")[0])
```

To add encrypted fields to a store that's already built, such as a Spring bean, use
`store.withEncryptedField("email", key)`: a view over the same client. With Spring Data, mark the record component
`@Encrypted("<key bean>")`; see [Spring Data repositories](../spring-boot.md#spring-data-repositories).

**What the database holds.** Each encrypted field is a binary value: HLXS sealed data (AES-256-GCM, see
[Keys and encryption](keys.md)), bound to the table, the item's key and the field's name, so a value copied to
another item or another field fails to open with `DataIntegrityException`. The value inside keeps its type exactly,
on every store: a `BigDecimal` reads back with its scale, even on Cosmos DB and Firestore, which store unencrypted
decimals as doubles. A sealed field is larger than its value by a header (about a hundred bytes, plus the wrapped data
key: about 256 bytes on Azure's RSA-2048 keys) and a 16-byte tag; count that against the item size limits below.

**Key service calls.** All encrypted fields of one document write share one data key, wrapped once, so a `put` or
`update` costs one wrap call however many fields it encrypts; each field is still sealed on its own, with its own
subkey and nonces. A read unwraps each document's data key once, so a `get` costs one call and a 100-item query page
100. A page's data keys are unwrapped in parallel, 16 at a time, so the page waits for about one round trip per 16
items rather than one per item. Data keys live only for the call: nothing is cached unless you turn on the key
store's `dataKeyCache`.

**Read-heavy tables.** For tables read far more often than written, turn on `dataKeyCache` on the key store: repeat
reads of the same items then make no call at all. It keeps unwrapped data keys in memory, which is the trade-off; see
[Performance and caches](keys.md#performance-and-caches). `keys.statistics()` shows the calls made and saved.

**What can't be encrypted.** Key attributes and index attributes, since the store needs them in the clear to find
and query items; `build()` refuses them. An encrypted field can't be incremented, since the store can't add to a
value it can't read: `update` throws `InvalidArgumentException`. Queries only ever filter on keys and indexes, so
they're unaffected.

**Turning it on for an existing table.** Items written before a field was encrypted read as before while they don't
have the field. A field stored in the clear where the table expects it sealed is refused with
`InvalidArgumentException`, never returned: rewrite the item through the encrypted store (read it with a plain store,
`put` it with the encrypted one). Any key of the field's key store opens what any other sealed, so moving a field to
a new key is a configuration change; existing items keep opening with the old key until they're rewritten.

<details markdown="block">
  <summary>The encoding of a field's value</summary>

The sealed plaintext is a version byte (1) and a tagged value; integers are big-endian, lengths and counts are u32:
`0` null, `1` string (length, UTF-8), `2` boolean (one byte), `3` long (i64), `4` double (IEEE 754), `5` decimal
(scale i32, length, unscaled two's complement), `6` binary (length, bytes), `7` list (count, values), `8` document
(count, then length, UTF-8 name and value per field). The AAD is `halyx-field/1`, then the table name, the partition
key, a byte saying whether a sort key follows, the sort key, and the field name, each UTF-8 with a u32 length.
</details>

## Asynchronous calls

`async()` gives the store's asynchronous view: `get`, `put`, `update`, `delete`, `query` and `queryIndex` as
`CompletableFuture`s, with the same checks, conditions, expiry handling and errors as the blocking calls.

```java
orders.async().put(key, document, WriteOptions.ifNotExists())
        .thenCompose(item -> orders.async().query(Query.partition(customer)))
        .thenAccept(page -> log.info("{} orders", page.items().size()));
```

They're non-blocking on DynamoDB (`DynamoDbAsyncClient` over Netty, created on the first asynchronous call), Cosmos
DB (the SDK's async client) and Firestore (its own futures), except where a call needs a read-then-write: `update`
on Cosmos DB and Firestore, and a versioned `put` on Firestore, which runs in a transaction. Those, and every call on
the memory provider, run the blocking call on a virtual thread, or on the executor given to `async(executor)`.
Batches and transactions are on `blocking()`. The reactive views use it.

## Provider details

| | DynamoDB | Cosmos DB (NoSQL API) | Firestore (native mode) |
|---|---|---|---|
| Table | table with string `pk` (and `sk`) keys | container partitioned on `/pk` | collection; sorted items in `<table>/<pk>/<table>/<sk>` |
| Version | `halyx_version` attribute | ETag | update time |
| Expiry | `halyx_expires` (epoch seconds): enable TTL on it | `ttl` (container default TTL must be on, for example -1) | `halyx_expires` Timestamp: add a TTL policy |
| Query | key condition on `sk` | SQL within the partition, `ORDER BY sk` | ordered on the `halyx_sort` field |
| Transaction | `TransactWriteItems` | transactional batch | read-check-write transaction |
| Index | a global secondary index of that name (eventually consistent) | a cross-partition query (default indexing) | a collection-group query: add a composite index on the index fields |
| Decimals | exact | exact (stored as a string wrapper) | stored as doubles |
| Item size | 400 KB | 2 MB | about 1 MiB |
| Encrypted fields | binary (`B`) | base64 in a binary wrapper, a third larger | bytes (`Blob`) |
| Reads | strongly consistent | the account's consistency level | strongly consistent |

On Cosmos DB, item ids can't contain `/ \ ? #`; Halyx escapes them (with `~`), so any key string works.
Keys with those characters are covered by the conformance kit on every provider.

## Spring Data repositories

Records can also be stored through Spring Data repositories, with derived queries on the keys and indexes,
optimistic locking and keyset scrolling. See [Spring Data repositories](../spring-boot.md#spring-data-repositories).

## Native clients

```java
DynamoDbClient dynamo = orders.unwrap(DynamoDbClient.class);          // AWS
CosmosContainer container = orders.unwrap(CosmosContainer.class);     // Azure
Firestore firestore = orders.unwrap(Firestore.class);                 // Google Cloud
```

See [Capabilities and native clients](capabilities-and-unwrap.md).
