# AWS to Halyx

From Spring Cloud AWS 3.x (`io.awspring.cloud`) and the AWS SDK v2 (v1 maps the same way). Use provider type `aws`.
Resources keep their names: bucket, table, topic and queue names stay as they are.

## Configuration

| Before | After |
|---|---|
| `spring.cloud.aws.region.static: eu-west-2` | `halyx.providers.aws.region: eu-west-2` |
| `spring.cloud.aws.credentials.access-key` / `secret-key` | Prefer the default chain (drop them). If keys are required: `halyx.providers.aws.credentials: { type: static, id, secret }`, fed from a secret |
| an assumed role (`StsAssumeRoleCredentialsProvider`) | `credentials: { type: impersonate, target: <role ARN>, external-id, session-name }` |
| `spring.cloud.aws.endpoint`, `spring.cloud.aws.<service>.endpoint` (LocalStack) | `halyx.providers.aws.endpoint`, plus `properties.aws.s3.path-style: true` for S3 on emulators |
| `spring.config.import: aws-parameterstore:/config/orders/` | `spring.config.import: halyx:settings`, with `halyx.parameter-stores.settings: { kind: parameters, prefix: config/orders }` |
| `spring.config.import: aws-secretsmanager:/secret/orders` | `halyx:secrets`, with `halyx.parameter-stores.secrets: { kind: secrets, prefix: secret/orders }`. JSON secrets expand into fields (`db.password`) |

Halyx's portable names are segments separated by `/` (`orders/db/password` is the SSM parameter
`/orders/db/password`). Existing names that don't fit, such as dots or underscores, need `native-names: true` on the
store; property keys then follow the native names. Check them before choosing.

## Object storage: S3

```java
// Before: S3Template or S3Client
s3Template.upload("acme-invoices", "2026/42.pdf", inputStream);
S3Resource r = s3Template.download("acme-invoices", "2026/42.pdf");
s3Client.getObject(b -> b.bucket("acme-invoices").key(k), ResponseTransformer.toBytes());
s3Template.deleteObject("acme-invoices", k);
URL url = s3Template.createSignedGetURL("acme-invoices", k, Duration.ofMinutes(10));

// After: halyx.blob-stores.invoices.bucket: acme-invoices
@Qualifier("invoices") BlobStore invoices;
invoices.put(BlobKey.of("2026/42.pdf"), BlobContent.ofStream(inputStream, length));
try (Blob blob = invoices.get(BlobKey.of("2026/42.pdf"))) { ... }
byte[] bytes = invoices.getBytes(key);
invoices.delete(key);
URI uri = invoices.presignDownload(key, Duration.ofMinutes(10)).uri();
```

`NoSuchKeyException` and a `404` become `NotFoundException`. Content type, metadata, conditional writes (`ifMatch`,
`ifNotExists`) and storage classes go in `PutOptions`. `listObjectsV2` paging becomes `list(ListOptions)` or
`listAll(prefix)`.

## Documents: DynamoDB

| Before | After |
|---|---|
| `DynamoDbTemplate` or `DynamoDbEnhancedClient` with `@DynamoDbBean` classes | a record with `@DocumentTable`, `@PartitionKey` and `@SortKey`, and a `DocumentRepository<Order, Key>` (`String` id without a sort key) |
| `dynamoDbTemplate.save(order)`, `load(key, Order.class)`, `delete(...)` | `repository.save`, `findById`, `deleteById` |
| `query(QueryEnhancedRequest...)` on the partition key | a derived query: `findByCustomerIdOrderByOrderIdDesc(...)` |
| a global secondary index query | declare the index under `halyx.document-stores.<key>.indexes`, then derive `findBy<IndexAttribute>...` |
| `@DynamoDbVersionAttribute` | `@Version dev.halyx.nosql.Version` (Halyx stores it as `halyx_version`) |
| conditional writes, `ConditionalCheckFailedException` | `WriteOptions.ifNotExists()` and `ifVersion(...)`; `PreconditionFailedException` (repositories: `DuplicateKeyException`, `OptimisticLockingFailureException`) |
| `transactWriteItems` | `DocumentStore.transact(...)` |
| `UpdateItem` with an update expression | `DocumentStore.update(key, DocumentUpdate.builder().set(...).increment(...).build())` |

**Existing tables:** find the table's real name first. Spring Cloud AWS's `DynamoDbTemplate` names a table after
the entity class in snake case (`Invoice` is `invoice`, `OrderLine` is `order_line`), with
`spring.cloud.aws.dynamodb.table-prefix` and `table-suffix`, unless a custom `DynamoDbTableNameResolver` is
registered. Set that name as `table`. Then set `partition-key` and `sort-key` to the table's key attribute names
(the `@DynamoDbPartitionKey` and `@DynamoDbSortKey` properties, unless `@DynamoDbAttribute` renames them). Keys must be strings
(numeric DynamoDB keys need a data migration). Halyx adds `halyx_version` when it writes a versioned item, and uses
`halyx_expires` for TTL (`aws.dynamodb.ttl-attribute` renames it). Check how existing items' attributes are named and
typed against the entity's components; see [differences.md](differences.md#existing-data).

## Messaging: SNS and SQS

```java
// Before
snsTemplate.convertAndSend("orders", orderJson);                                // or snsClient.publish(...)
sqsTemplate.send(to -> to.queue("orders-billing").payload(orderJson));
@SqsListener("orders-billing") void onOrder(String body) { ... }

// After: halyx.topics.order-events.name: orders     halyx.subscriptions.billing.name: orders-billing
@Qualifier("order-events") Publisher orderEvents;
orderEvents.publish(OutgoingMessage.builder(orderJson).attribute("type", "placed").build());
@HalyxListener("billing") void onOrder(String body) { ... }      // return = delete; throw = redeliver with backoff
```

- A queue fed by an SNS topic: Halyx reads SNS envelopes and raw delivery alike. Raw delivery is recommended.
- Sending straight to a queue (no topic): `halyx.topics.<key>.properties.aws.target: sqs`, with `name` set to the
  queue.
- FIFO: `.fifo` names, `orderingKey` (the message group) and `deduplicationId`.
- Delays: `.delay(...)` on SQS only, not SNS (`DELAYED_DELIVERY`).
- Visibility timeout and acknowledgement mode: Halyx extends leases while a handler runs; `MANUAL` acknowledgement
  becomes a `ReceivedMessage` parameter with `ack()` and `nack(delay)`.
- `listener.concurrency`, `max-backoff` and `batch-size` replace the listener container settings.
- Large payloads stored in S3 (the extended client): `claim-check: { blob-store, threshold }` on both the topic and
  the subscription.

## Events: EventBridge

`eventBridgeClient.putEvents(...)` becomes `EventPublisher.publish(CloudEvent)`, with
`halyx.event-buses.<key>.name: <bus>`. Halyx sends CloudEvents 1.0: the event's `source` and `type` become the
entry's `Source` and `DetailType`, so rules on those still match, but `detail` holds **the whole CloudEvent**, with
the old payload under `data`. Rules that match on payload fields (`detail.status`) must become `detail.data.status`,
and consumers outside Halyx must read `detail.data`. Plan that change with the rule owners. Halyx consumers receive
through the rule's target queue: `halyx.subscriptions.<key>.name: <queue>`, with a `CloudEvent` listener parameter.
They also decode AWS service events (with `detail-type` as the type and `detail` as the data).

## Streams: MSK

- Spring Kafka with `aws-msk-iam-auth` (`sasl.jaas.config: ...IAMLoginModule required;`): drop the SASL settings and
  `spring.kafka.bootstrap-servers`, define `halyx.stream-clusters.events: { bootstrap-servers, auth: { type: iam } }`,
  and set `halyx.spring-kafka.cluster: events`. `KafkaTemplate` and `@KafkaListener` stay as they are.
- Or move to `StreamProducer` and `@HalyxListener` on `halyx.stream-consumers` for retries and dead letters in Halyx.

## Caches: ElastiCache

`spring.data.redis.*` with an ElastiCache endpoint: for key-value use, a `halyx.caches.<key>` bean with
`auth: { type: iam, username }` and `properties: { aws.elasticache.cache-name: <replication group> }`. For
`@Cacheable`, `halyx.cache-manager` with the same settings. `RedisTemplate` features beyond key-value, TTLs,
counters and locks (streams, pub/sub, sorted sets, Lua) have no Halyx equivalent: keep Spring Data Redis for them.

## SQL: RDS and Aurora

`jdbc:aws-wrapper:postgresql://...?wrapperPlugins=iam`, or a hand-built token provider: `halyx.databases.orders:
{ engine: postgresql, host, database, auth: { type: iam, username } }`. Remove `spring.datasource.url`, `username`
and `password`; Boot's `DataSourceAutoConfiguration` backs off. Passwords from Secrets Manager:
`auth: { type: password, username, secret: { store: secrets, name: orders/db, field: password } }`. The AWS Advanced
JDBC Wrapper's failover plugins have no Halyx equivalent yet: point `host` at the cluster endpoint.

## No Halyx equivalent

Kinesis Data Streams, Step Functions, Lambda invocations, SES, Cognito, KMS encrypt and decrypt, S3 Select, S3 event
notifications configuration, DynamoDB Streams, scans and PartiQL. Keep these on the SDK, behind one class per feature,
and list them in the report.
