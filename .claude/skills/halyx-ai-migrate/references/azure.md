# Azure to Halyx

From Spring Cloud Azure 5.x and later (`com.azure.spring`) and the Azure SDK for Java. Use provider type `azure`.
Halyx logs in with Microsoft Entra ID through `DefaultAzureCredential`: managed identities, Workload ID, the Azure
CLI. Keys and connection strings are for emulators only. Resources keep their names.

## Configuration

| Before | After |
|---|---|
| `spring.cloud.azure.storage.blob.account-name` / `endpoint` | `halyx.providers.azure.properties.azure.storage.account` |
| `spring.cloud.azure.cosmos.endpoint`, `.database` | `azure.cosmos.endpoint`, `azure.cosmos.database` provider properties |
| `spring.cloud.azure.servicebus.namespace` | `azure.servicebus.namespace: <name>.servicebus.windows.net` |
| `spring.cloud.azure.credential.*`, `client-id` for a user-assigned identity | the default chain. It takes a user-assigned identity's client id from the `AZURE_CLIENT_ID` environment variable, so that setting moves to the deployment (workload identity on AKS sets it already). For an Entra application outside Azure, `credentials: { type: service-principal, tenant-id, client-id, secret \| certificate \| token-file }` |
| Key Vault property sources (`spring.cloud.azure.keyvault.secret.property-sources[0].endpoint`) | `spring.config.import: halyx:secrets`, with `halyx.parameter-stores.secrets: { kind: secrets, location: <vault URL> }` |
| App Configuration (`spring.cloud.azure.appconfiguration.stores[0].endpoint`, key filters, labels) | `halyx:settings`, with `halyx.parameter-stores.settings: { kind: parameters, location: <endpoint>, prefix, properties: { azure.appconfig.label: <label> } }` |

Key Vault names allow only letters, digits and dashes: the portable name `orders/db/password` is stored as
`orders--db--password`. Existing secrets with other names need `native-names: true`. App Configuration keys are
used as they are (`orders/db/password`); keys with `:` or `.` separators need `native-names: true`.

## Object storage: Blob Storage

```java
// Before: BlobServiceClient / BlobContainerClient, or a Resource "azure-blob://container/name"
container.getBlobClient(name).upload(BinaryData.fromBytes(bytes), true);
byte[] data = container.getBlobClient(name).downloadContent().toBytes();
container.getBlobClient(name).deleteIfExists();
String sas = blob.generateSas(new BlobServiceSasSignatureValues(expiry, permissions));

// After: halyx.blob-stores.invoices.bucket: <container>
invoices.put(BlobKey.of(name), BlobContent.ofBytes(bytes));
byte[] data = invoices.getBytes(BlobKey.of(name));
invoices.delete(BlobKey.of(name));
URI uri = invoices.presignDownload(BlobKey.of(name), Duration.ofMinutes(10)).uri();   // a SAS URL
```

`BlobStorageException` with status `404` (`BlobNotFound`) becomes `NotFoundException`, and `412` becomes
`PreconditionFailedException`. Access tiers map to `StorageTier` in `PutOptions`.

## Documents: Cosmos DB (NoSQL API)

| Before | After |
|---|---|
| `@Container(containerName = "orders")` entity, `@PartitionKey` field, `@Id` | a record with `@DocumentTable("<document store key>")`, `@PartitionKey`, and `@SortKey` when items within a partition are distinct |
| `CosmosRepository<Order, String>` | `DocumentRepository<Order, Key>` (or `String` without a sort key) |
| derived queries across partitions, `@Query` with SQL | queries on the partition key and the sort key, or a declared index (a cross-partition query). Arbitrary SQL has no equivalent: rewrite it with indexes, or keep it on `unwrap(CosmosContainer.class)` |
| `@Version` on `_etag` | `@Version dev.halyx.nosql.Version` (Halyx uses the ETag) |
| `CosmosTemplate.patch` | `DocumentStore.update(key, DocumentUpdate...)` |
| transactional batches | `DocumentStore.transact` (within one partition, as on Cosmos) |

**Existing containers:** set `partition-key` to the container's partition key path (without the `/`). Halyx keeps
each item's `id` as its sort key, or as its partition key on tables without a sort key, escaping `/ \ ? #` with `~`.
Spring Data Cosmos entities usually have a UUID `@Id` and a separate `@PartitionKey` field: map them with
`partition-key: <that field>` and **`sort-key: id`**, and make the record's components `@PartitionKey <field>` and
`@SortKey id`. Existing items then read as they are, which was tested with items written by Spring Data Cosmos. Ids
that contain `/ \ ? #` need a migration. Other fields keep their names.

Spring Data Cosmos's repositories and Halyx's coexist while both are on the classpath: each claims only the
repositories that extend its own types (`CosmosRepository` or `DocumentRepository`), so entities can move one at a
time.

## Messaging: Service Bus

```java
// Before
serviceBusTemplate.send("orders", MessageBuilder.withPayload(json).build());
@ServiceBusListener(destination = "orders", group = "billing") void onOrder(String body) { ... }   // with @EnableAzureMessaging

// After (and drop @EnableAzureMessaging): halyx.topics.order-events.name: orders
//        halyx.subscriptions.billing.name: orders/subscriptions/billing     (a queue: just its name)
orderEvents.publish(OutgoingMessage.builder(json).build());
@HalyxListener("billing") void onOrder(String body) { ... }      // return = complete; throw = abandon, redelivered
```

- Sending to a queue: `halyx.topics.<key>.properties.azure.servicebus.entity: queue`.
- Sessions: `properties.azure.servicebus.sessions: true` on the topic and the subscription. The session id is
  `orderingKey`.
- Duplicate detection: `deduplicationId` (the `MessageId`). Scheduled messages: `.delay(...)`.
- `nack(delay)` redelivers at once on Service Bus (no `NACK_DELAY`). A handler that relied on
  `abandon` with a delay or on deferral needs rethinking.
- Dead-lettering is by the entity's max delivery count, as before.
- `ServiceBusProcessorClient` settings (`maxConcurrentCalls`, prefetch) become `listener.concurrency` and
  `batch-size`.

## Events: Event Grid

Halyx supports **Event Grid namespaces** (namespace topics) and CloudEvents 1.0, and receives through pull (queue)
subscriptions.
`EventGridPublisherClient` on a namespace topic becomes `EventPublisher` with `halyx.event-buses.<key>: { name:
<topic>, properties: { azure.eventgrid.endpoint: https://<namespace host> } }`. A pull (queue) subscription is
`halyx.subscriptions.<key>: { name: <subscription>, topic: <topic>, properties: { azure.eventgrid.endpoint: ... } }`.
**Basic Event Grid topics and the Event Grid schema have no Halyx equivalent:** moving to a namespace topic is an
infrastructure change and a format change for consumers. Raise it in the plan.

```java
// Before: azure-messaging-eventgrid-namespaces
sender.send(new com.azure.core.models.CloudEvent("/bookings", "com.acme.booking.confirmed",
        BinaryData.fromObject(data), CloudEventDataFormat.JSON, "application/json").setSubject(id));
// After: halyx.event-buses.booking-events: { name: bookings, properties: { azure.eventgrid.endpoint: https://... } }
bookingEvents.publish(CloudEvent.builder("com.acme.booking.confirmed", "/bookings").subject(id).json(json).build());
```

Both are CloudEvents 1.0 on the namespace topic, so subscribers see the same type, source, subject and data. The
`EventGridSenderClient` bean and its `TokenCredential` go.

## Streams: Event Hubs (Kafka endpoint)

Spring Kafka against `<namespace>.servicebus.windows.net:9093` with OAUTHBEARER (Spring Cloud Azure's passwordless
Kafka, which `spring-cloud-azure-starter` adds when the bootstrap servers are an Event Hubs namespace):
`halyx.stream-clusters.events: { bootstrap-servers: <namespace>.servicebus.windows.net:9093, auth: { type:
iam } }` and `halyx.spring-kafka.cluster: events`, in the cloud profile. `KafkaTemplate`, `@KafkaListener`, the
serializers and the group ids stay. Code using the native `EventHubProducerClient` and
`EventProcessorClient` (AMQP, checkpoints in Blob Storage) moves to `StreamProducer` and `@HalyxListener` on the Kafka
endpoint. Offsets then live in Kafka consumer groups, not the checkpoint store, so plan where consumers start.

## Caches: Azure Cache for Redis, Azure Managed Redis

`spring.data.redis.*`, with `azure.passwordless-enabled` (`spring-cloud-azure-starter-data-redis-lettuce`) or an
access key, becomes `halyx.caches.<key>: { endpoint: rediss://<host>:<port>, auth: { type: iam, username: <the
identity's object id> } }`, or `halyx.cache-manager` for `@Cacheable` (with `codec: jdk` for Spring's default
serializer, and `trusted-packages`). Keep existing `StringRedisTemplate` keys with `namespace: ""`. The identity needs a data access policy assignment on the cache.

## SQL: Azure Database for PostgreSQL and MySQL, Azure SQL

`spring.datasource.azure.passwordless-enabled: true` (`spring-cloud-azure-starter-jdbc-postgresql` or `-mysql`):
`halyx.databases.orders-db: { engine: postgresql | mysql | sqlserver, host, database, auth: { type: iam, username:
<the Entra principal's database role> } }`, the host from the JDBC URL. Halyx's IAM login turns on TLS, so
`sslmode=require` needs no setting.

## No Halyx equivalent

Queue Storage, Table Storage, Azure Files, Event Grid basic topics and system topics, Functions bindings, Cosmos DB
change feed, Gremlin, Cassandra and MongoDB APIs, Notification Hubs, and SignalR. Keep these on the SDK and list them.
