# Google Cloud to Halyx

From Spring Cloud GCP 5.x and later (`com.google.cloud:spring-cloud-gcp-*`) and the Google Cloud client libraries.
Use provider type `gcp`, with `properties.gcp.project-id` (the default is Application Default Credentials'
project). Halyx logs in with Application Default Credentials: Workload Identity, the metadata server, gcloud.
Resources keep their names.

## Configuration

| Before | After |
|---|---|
| `spring.cloud.gcp.project-id` | `halyx.providers.gcp.properties.gcp.project-id` |
| `spring.cloud.gcp.credentials.location` (a key file) | Prefer Workload Identity or ADC; to act as another service account, `credentials: { type: impersonate, target: <email> }` |
| `spring.config.import: sm://` and `${sm://db-password}` | `spring.config.import: halyx:secrets`, with `halyx.parameter-stores.secrets: { kind: secrets }`; then `${db-password}` |
| Parameter Manager | `halyx.parameter-stores.settings: { kind: parameters, location: global }` (or a region) |
| emulator hosts (`spring.cloud.gcp.pubsub.emulator-host`, `firestore.emulator.enabled`) | `halyx.providers.gcp.endpoint`, with `credentials.type: anonymous` |

Secret Manager and Parameter Manager names: the portable `orders/db/password` is stored as `orders__db__password`.
A single segment (`db-password`) stays as it is. Existing names with underscores need `native-names: true`.

## Object storage: Cloud Storage

```java
// Before: Storage, or @Value("gs://acme-invoices/2026/42.pdf") Resource
storage.create(BlobInfo.newBuilder("acme-invoices", name).setContentType("application/pdf").build(), bytes);
byte[] data = storage.readAllBytes("acme-invoices", name);
storage.delete("acme-invoices", name);
URL url = storage.signUrl(BlobInfo.newBuilder("acme-invoices", name).build(), 10, TimeUnit.MINUTES);

// After: halyx.blob-stores.invoices.bucket: acme-invoices
invoices.put(BlobKey.of(name), BlobContent.ofBytes(bytes), PutOptions.builder().contentType("application/pdf").build());
byte[] data = invoices.getBytes(BlobKey.of(name));
invoices.delete(BlobKey.of(name));
URI uri = invoices.presignDownload(BlobKey.of(name), Duration.ofMinutes(10)).uri();
```

Signed URLs need credentials that can sign (`PRESIGNED_URL`): a service account key, or impersonation with
`iam.serviceAccounts.signBlob`. Check the capability rather than assuming it.

## Documents: Firestore

| Before | After |
|---|---|
| `@Document(collectionName = "orders")` with `@DocumentId` | a record with `@DocumentTable("<document store key>")` and `@PartitionKey` (and `@SortKey` for items within a partition) |
| `FirestoreReactiveRepository<Order>` (Spring Cloud GCP's repositories are reactive) | `ReactiveDocumentRepository` (with `halyx-reactor`) in reactive code. Where the service `.block()`s on every call, as Spring MVC services often do, the blocking `DocumentRepository`, which drops the `.block()` calls |
| `FirestoreTemplate`, or the client's `collection(...).document(...)` | `DocumentStore` (`get`, `put`, `update`, `query`) |
| queries on arbitrary fields, `whereEqualTo(...).orderBy(...)` | the partition and sort keys, or a declared index (a collection-group query, which needs its composite index). Others: rewrite, or keep them on `unwrap(Firestore.class)` |
| transactions | `DocumentStore.transact` |

**Existing collections:** a table keyed only by partition is the collection itself, `<collection>/<partition>`, and
Halyx reads the key from the document id. So a flat collection, as Spring Cloud GCP writes it (`@DocumentId` and
the other fields), reads as it is: map it with `@PartitionKey id` and leave the fields' names alone. This was tested
with documents written by Spring Cloud GCP. Ids with `/`, or starting with `__`, need escaping, and so a migration. A table
with a sort key stores items in subcollections, `<collection>/<partition>/<collection>/<sort>`, which existing data
rarely matches, so plan a migration or keep the partition-only shape. Decimals are stored as doubles: amounts read
back without their scale. Use the run's own database with `gcp.firestore.database`. See
[differences.md](differences.md#existing-data).

## Messaging: Pub/Sub

```java
// Before
pubSubTemplate.publish("orders", json);
pubSubTemplate.subscribe("orders-billing", message -> { bill(message.getPubsubMessage().getData().toStringUtf8()); message.ack(); });
// or a PubSubInboundChannelAdapter with @ServiceActivator, a Spring Cloud Stream binding, or a subscribe(...) started
// on ApplicationReadyEvent and stopped in @PreDestroy: Halyx's listener lifecycle replaces all of that

// After: halyx.topics.order-events.name: orders     halyx.subscriptions.billing.name: orders-billing
orderEvents.publish(json);
@HalyxListener("billing") void onOrder(String body) { bill(body); }     // return = ack; throw = nack with backoff
```

- Ordering keys need an ordering-enabled subscription (`ORDERING`). Pub/Sub has no deduplication or delayed
  delivery: `deduplicationId` and `.delay(...)` fail with `UnsupportedFeatureException`, so keep idempotent
  consumers.
- Attributes are message attributes, as before.
- `maxAckExtensionPeriod` and flow control become Halyx's lease extension and `listener.concurrency`.
- Payloads over 10 MB, or a pattern of blobs in Cloud Storage: `claim-check: { blob-store, threshold }`.

## Events: Eventarc Advanced

Halyx publishes CloudEvents to an **Eventarc Advanced message bus** (`halyx.event-buses.<key>.name: <bus resource
name>`). Consumers read the Pub/Sub subscription its pipeline delivers to. Eventarc Standard triggers, and events
published straight to Pub/Sub as a CloudEvents convention, aren't a bus: keep those as messaging.

## Streams: Managed Service for Apache Kafka

Spring Kafka with Google's login callback handler (`GcpLoginCallbackHandler`, `SASL_SSL` with `OAUTHBEARER`):
`halyx.stream-clusters.events: { bootstrap-servers: bootstrap.<cluster>.<region>.managedkafka.<project>.cloud.goog:9092,
auth: { type: iam } }` and `halyx.spring-kafka.cluster: events`. Halyx finds the principal from the credentials; set
`properties.gcp.kafka.principal` only for user credentials.

## Caches: Memorystore

`spring.data.redis.*` with Memorystore: `halyx.caches.<key>: { endpoint: rediss://<ip>:<port>, auth: { type: iam } }`
for IAM auth (the identity needs `roles/memorystore.dbConnectionUser`). With in-transit encryption, the server CA:
`properties: { redis.ca-certificate: <path to the instance's CA PEM>, redis.verify-peer: ca }`.

## SQL: Cloud SQL

`spring-cloud-gcp-starter-sql-postgresql` (`spring.cloud.gcp.sql.instance-connection-name`,
`database-name`): `halyx.databases.orders: { engine: postgresql, database, auth: { type: iam, username: <service
account email without .gserviceaccount.com> }, properties: { gcp.sql.instance: <project:region:instance> } }`, with
`com.google.cloud.sql:postgres-socket-factory` at runtime (the connector). Or connect to the instance's IP with `host`
and no connector. MySQL takes the full email as the username.

## No Halyx equivalent

Datastore mode, Spanner, Bigtable, BigQuery, Cloud Tasks, Cloud Scheduler, Firebase Auth, the Firestore real-time
listeners, Storage notifications configuration, and Eventarc Standard triggers. Keep these on the client libraries and
list them.
