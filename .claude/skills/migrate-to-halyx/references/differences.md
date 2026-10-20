# Differences a migrated service must handle

Each of these has caught a real migration or a real-cloud run. Check every one against the inventory, and put the
ones that apply in the plan.

## Existing data

Halyx reads and writes data in a defined layout. Existing data is readable only where it already fits that
layout. Read a few real items or objects before deciding.

| Store | Halyx's layout | Compatible when | Otherwise |
|---|---|---|---|
| DynamoDB | string keys in the configured `partition-key` and `sort-key` attributes; `halyx_version` for versions; `halyx_expires` for TTL | the keys are strings, and attribute names and types match the entity's components | migrate the keys, or keep the table on `unwrap(DynamoDbClient.class)` |
| Cosmos DB | partition key in the configured attribute; `id` is the sort key, or the partition key on tables without one, escaped with `~` | the container's partition path is that attribute and the existing `id`s follow the rule | migrate the items, or keep the container on `unwrap` |
| Firestore | partition-only tables: `<collection>/<partition>`; sorted tables: `<collection>/<partition>/<collection>/<sort>` | partition-only, with document ids equal to the partition key | migrate, or keep on `unwrap` |
| Blob stores | object keys as they are; user metadata as the provider's metadata | almost always | — |
| Parameters and secrets | portable names, mapped to each backend's naming scheme (see the cloud's mapping file) | the names fit that mapping | `native-names: true` |
| Messages | the body as bytes, attributes as message attributes | producers and consumers agree on the body | migrate both ends together, or keep the body format |

Values: records map `BigDecimal`, `Instant`, `LocalDate`, `UUID`, enums, lists, maps and nested records. Fields the
old serialiser wrote differently (dates as epoch numbers, enums as ordinals, custom converters) need a look before
the entity is changed.

## Behaviour

| Area | Difference |
|---|---|
| **Decimals** | No store keeps a decimal's scale: `3.50` reads back as `3.5` (seen on DynamoDB). Firestore stores decimals as doubles, and so do Spring Data repositories on Cosmos DB (`DocumentStore` on Cosmos keeps them exact). Format amounts with an explicit scale, compare with `compareTo`, not `equals`, and store money as minor units where precision matters |
| **Ordering** | per key: SQS and SNS FIFO message groups, Service Bus sessions, Pub/Sub ordering keys on ordering-enabled subscriptions |
| **Deduplication** | FIFO queues and topics, and Service Bus duplicate detection. Pub/Sub has none, so consumers must be idempotent |
| **Delays** | SQS (up to 15 minutes) and Service Bus. Not SNS or Pub/Sub |
| **`nack(delay)`** | honoured on SQS and Pub/Sub; Service Bus redelivers at once |
| **Message size** | 256 KiB on SQS, SNS and Service Bus standard; 10 MB on Pub/Sub. Use claim checks for anything larger |
| **Delivery** | at least once everywhere: handlers must tolerate redelivery |
| **Listener errors** | returning acks; throwing redelivers with backoff until the entity's dead-letter rule takes over. There's no "ack and log" by default, so catch what shouldn't be retried |
| **Consistency** | DynamoDB index queries and Secrets Manager listings are eventually consistent; Halyx never waits on the caller's behalf. Retry `UnavailableException` |
| **Events** | Halyx sends CloudEvents 1.0. On EventBridge, `detail` is the whole CloudEvent, so rules and consumers matching on the old detail move to `detail.data` |
| **Bean names** | every `halyx.*` key is a bean name. Classes named like a key (`Receipts` next to `halyx.blob-stores.receipts`) fail at startup, naming both |

## Permissions

Halyx needs a few permissions the old code may not have. Grant them when the IAM policy is narrow:

- **Health checks:** each parameter store's indicator reads a probe name, `<prefix>/halyx-ping`, and expects "not
  found". A policy scoped to single secrets returns "access denied" instead, and health goes DOWN. Allow reads under
  the store's prefix (`secret:<prefix>/*` on Secrets Manager).
- **Blob store health** lists one item: `s3:ListBucket`, or the equivalent.
- **SNS topics named, not ARNs:** resolving a name calls `sns:ListTopics`. Configure the topic's ARN to avoid that.
- **SQS:** `GetQueueUrl` and `GetQueueAttributes` alongside receive, delete and change-visibility.
- **Secrets Manager `getAll`** (config imports with reload): `ListSecrets` and, for batches, `BatchGetSecretValue`.

## Runtime

- **Buildpack images** cap direct memory at 10 MB, which the cloud SDKs' Netty clients exceed under load (seen with
  the Azure SDK). Set `JAVA_TOOL_OPTIONS=-XX:MaxDirectMemorySize=64M` or more.
- **Several providers in one jar** is fine. Each provider is used only when configured, and the cloud is chosen by
  profile.
