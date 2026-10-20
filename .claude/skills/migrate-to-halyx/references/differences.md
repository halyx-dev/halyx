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
| `@Cacheable` entries | `halyx.cache-manager` keys `<cache>:<key>` (with `namespace-prefix` in front), values in its `jdk` or `json` codec (`{"@class", "v"}`) | never: Spring's `RedisCacheManager` keys `<cache>::<key>` | nothing to do for the service: old entries are misses, never errors, and expire with their TTL. Anything else that reads the entries (dashboards, other services) needs the new format |
| `Cache` beans | `<namespace>:<key>`, the namespace **defaulting to the bean name** | `namespace: ""` (or the old prefix) and the same value format | without it, new instances use other keys: rate limits double during a rollout, and counters restart from zero |
| Counters | decimal strings, `INCRBY`; `increment(key, delta)` never sets an expiry, and `default-ttl` doesn't apply to it | the old code used `INCR`/`INCRBY` on plain keys | — |
| Kafka with Spring for Apache Kafka | unchanged: `KafkaTemplate`, serializers (including `JsonSerializer`'s type headers) and `@KafkaListener` groups stay Spring's | the group ids stay the same, so committed offsets carry over | a renamed group reads its `auto-offset-reset` position again |

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

## Rollout: old and new instances side by side

A rolling deploy runs both versions at once, so every shared format has to work both ways until the last old
instance stops. Plan these explicitly:

- **Events on EventBridge:** old instances publish the plain detail, new ones a CloudEvent with the detail under
  `detail.data`. Change the rules **before** deploying: patterns that match either,
  `"$or": [{"detail": {"tier": ["GOLD"]}}, {"detail": {"data": {"tier": ["GOLD"]}}}]`. Halyx consumers read both
  (a non-Halyx event is decoded with `detail` as the data). For consumers outside the service that read `detail.x`,
  split the rule on `"detail": {"specversion": [{"exists": true}]}` and rebuild today's envelope for the CloudEvents
  half with an input transformer (`InputPathsMap` on `$.detail.data.*`, an `InputTemplate` of the old shape). The
  other half, `{"exists": false}`, passes old events through as they are. One transformer for both formats doesn't
  work: on old events, its paths are missing and the output isn't valid JSON. Drop the old halves after the rollout.
- **Event Grid namespaces and Eventarc Advanced:** both sides are CloudEvents already. Keep the type, source,
  subject and data, and subscribers see no change.
- **Counters, rate limits and locks:** the same keys and formats (`namespace: ""`), so both versions count together.
- **Kafka:** the same topic, key, value format and group.
- **`@Cacheable`:** the versions don't share entries (different keys), so each fills its own; nothing breaks.

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
- **Old and new libraries in one jar, during the migration:** a provider module brings its cloud's SDK clients
  (`halyx-gcp` brings Firestore, Pub/Sub, Storage, Secret Manager, Parameter Manager and, with the connector, Cloud
  SQL). The old library's auto-configuration switches on for each one it finds, even for services the code never
  used. With Spring Cloud GCP, startup failed (`GcpProjectIdProvider` missing, `A database name must be
  provided`); elsewhere, it creates clients nobody asked for. Start the service after adding the dependencies, and
  turn those off until the old library goes: `spring.cloud.gcp.{firestore,pubsub,storage,secretmanager,parametermanager,sql}.enabled: false`.
- **Managed identity client id on Azure:** Halyx's default chain is `DefaultAzureCredential`, which takes a
  user-assigned identity's client id from `AZURE_CLIENT_ID`. Spring Cloud Azure's `credential.client-id` therefore
  moves from `application.yml` to the deployment's environment. Workload identity on AKS sets it already.
