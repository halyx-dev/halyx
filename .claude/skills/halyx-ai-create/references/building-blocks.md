# Building blocks: from a need to a Halyx resource

Pick the smallest set that covers the plan. Each row is a `halyx.*` group in `application.yml`; each key under it is
a bean, injected with `@Qualifier("<key>")`. The full reference is `docs/spring-boot.md`, and `docs/api/<area>.md`
for each area; check names there.

| Need | Halyx | AWS | Azure | Google Cloud |
|---|---|---|---|---|
| Files, documents, exports, anything large or binary | `blob-stores.<key>: { bucket }`, a `BlobStore` | S3 bucket | Blob Storage container | Cloud Storage bucket |
| Records read by key, by a partition and a range of sort keys, or by an index | `document-stores.<key>: { table, sort-key, indexes }`, a `DocumentStore`, and Spring Data `DocumentRepository` beans with `halyx-spring-data-document` | DynamoDB table | Cosmos DB (NoSQL API) container | Firestore (native mode) collection |
| Work for a known consumer, fanned out to several | `topics.<key>: { name }`, a `Publisher`; `subscriptions.<key>: { name }` with `@HalyxListener` | SNS topic, SQS queue subscribed with raw delivery | Service Bus topic and subscription | Pub/Sub topic and subscription |
| Announcing what happened, routed by rules to whoever subscribes | `event-buses.<key>: { name }`, an `EventPublisher` of `CloudEvent`s; a subscription per consumer | EventBridge bus, a rule targeting an SQS queue | Event Grid namespace topic, a pull event subscription | Eventarc Advanced bus, a pipeline to a Pub/Sub topic |
| A high-volume, ordered, replayable log; several consumer groups | `stream-clusters`, `stream-producers`, `stream-consumers`: a `StreamProducer`, and `@HalyxListener` on the consumer. Or `spring-kafka.cluster` for `KafkaTemplate` and `@KafkaListener` | MSK (IAM) | Event Hubs, Kafka endpoint (Entra ID) | Managed Service for Apache Kafka (Google IAM) |
| Fast keys with expiry: idempotency keys, counters, locks, sessions | `caches.<key>: { endpoint, auth }`, a `Cache<V>` | ElastiCache for Valkey or Redis (IAM user) | Azure Managed Redis (Entra ID) | Memorystore for Valkey (IAM, TLS) |
| Caching method results | `cache-manager`, Spring's `@Cacheable` | as above | as above | as above |
| Joins, reports, constraints, transactions across rows | `databases.<key>: { engine, host, database, auth }`, a `DataSource` for JDBC, JPA and Flyway; or `r2dbc-databases` | RDS or Aurora (IAM login) | Azure Database for PostgreSQL or Azure SQL (Entra ID) | Cloud SQL (IAM login, the Cloud SQL connector) |
| Settings that change without a deploy | `parameter-stores.<key>: { kind: parameters, prefix }`, and `spring.config.import: halyx:<key>` with `reload-interval` | SSM Parameter Store | App Configuration | Parameter Manager |
| Secrets: keys, passwords, tokens | `parameter-stores.<key>: { kind: secrets, prefix }` | Secrets Manager | Key Vault | Secret Manager |

## Choosing between neighbours

- **Messaging or events.** Messaging sends work to consumers the publisher knows about, with leases, retries and
  dead letters. Events announce a fact, and routing rules decide who hears it, so new consumers subscribe without
  the publisher changing. Pick events only when others outside the service will consume them, since Event Grid
  namespaces and Eventarc Advanced have no emulators (see below).
- **Messaging or streams.** Streams keep records for replay and in order per key, with consumer groups reading at
  their own pace; they need a Kafka cluster, the most expensive building block. Use them for high volume, audit
  logs and replay; use messaging for work items.
- **Document store or SQL.** A document store serves known access patterns cheaply and scales without tuning. Every
  query must name a partition, or a declared index's partition: anything else would scan, and Spring Data
  repositories refuse it at startup. Choose SQL when the plan has joins, ad hoc filters, reports or constraints across
  records. The memory provider has no SQL, so the `local` profile then needs PostgreSQL in a container.
- **`Cache` or `@Cacheable`.** `Cache<V>` for data the service owns in the cache (idempotency keys, counters, locks).
  `cache-manager` with `@Cacheable` for results computed elsewhere: set `fail-open: true`, so an unreachable cache
  makes them uncached rather than failing the writes that evict them. Halyx's health covers `Cache` beans, not the
  cache manager, so a broken `@Cacheable` backend doesn't show in `/actuator/health`.

## Document keys

- The partition key spreads load and scopes queries: the customer, tenant or account the reads are about. The sort
  key orders items within it. Sort keys compare as strings, so make them fixed width and add a random suffix where
  two can collide: `"%013d".formatted(epochMillis)`, or a timestamp with a fixed pattern
  (`DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)`). Not
  `DateTimeFormatter.ISO_INSTANT` or `Instant.toString()`: they drop trailing zeros, so `.08Z` sorts after `.080727Z`.
- A sort key can carry a prefix to query by: `<day>#<path>` makes one day's items a `StartingWith` query.
- A read by something other than the partition is a declared index (`indexes.<name>: { partition-attribute,
  sort-attribute }`), queried with `findBy<IndexPartition>...` or `queryIndex`. Index reads are eventually consistent
  on DynamoDB.
- **An index's partition spans the whole table**, not one tenant. "One tenant's orders in a status" needs a composite
  attribute such as `tenantStatus = <tenant>#<status>` as the index partition, and every write that changes the status
  (partial updates included) must set it too.
- `update` on a missing item fails with `NotFoundException`. A counter created on first use is a
  `put(..., WriteOptions.ifNotExists())` at zero (ignoring `AlreadyExistsException` or `PreconditionFailedException`
  from a concurrent creator), then the increment.
- The tables are infrastructure, with string key attributes `pk` and `sk`: a DynamoDB table with `pk` (and `sk`), a
  Cosmos DB container partitioned on `/pk`, and nothing to create on Firestore. A TTL needs the cloud's setting
  (DynamoDB TTL on `halyx_expires`, a Cosmos default TTL such as -1, a Firestore TTL policy).
- Item size: 400 KB on DynamoDB, 2 MB on Cosmos DB, about 1 MiB on Firestore. Put large content in a blob store and
  keep its key in the record.

## What differs

Each of these goes in the plan when the service uses it, with how the service stays correct on every target cloud.

| Topic | What differs | What the service does |
|---|---|---|
| Message size | 256 KiB on SQS, SNS and Service Bus standard; 10 MB on Pub/Sub | keep bodies small and send keys, or set `claim-check: { blob-store, threshold }` on the topic and subscription |
| Event size | about 256 KB on EventBridge, about 1 MB on Event Grid and Eventarc | the same; give every event data on Google Cloud |
| Delays (`delay`) | SQS queues (15 minutes) and Service Bus only; not SNS or Pub/Sub | don't plan on them for a topic; schedule work another way |
| Deduplication ids | FIFO SNS and SQS, Service Bus duplicate detection; not Pub/Sub | make the handler idempotent instead |
| Ordering keys | FIFO SNS and SQS, Service Bus sessions, Pub/Sub ordering-enabled subscriptions | each entity created that way per cloud; or streams, ordered per key everywhere |
| `nack(delay)` | redelivers at once on Service Bus | don't rely on the delay for back-off |
| Presigned URLs | on Google Cloud, only with a service account that can sign (`iam.serviceAccounts.signBlob`) | grant it, check the capability, or proxy the download |
| Stream transactions | not on Event Hubs | idempotent producers (on by default) and idempotent consumers |
| Decimals | document stores keep the value, not the scale (`3.50` reads back as `3.5`); Firestore and Cosmos DB repositories keep doubles | format amounts explicitly (`setScale(2)`, `toPlainString()`), compare with `compareTo`, or store minor units as `long` |
| Event routing | rules filter in the cloud; the memory provider's buses deliver every event | check the event's type and source in the listener too |
| Reads after writes | Cosmos DB follows the account's consistency; DynamoDB indexes are eventually consistent | a listener that can't read what it was told about throws, so the message is redelivered |
| No emulator | Event Grid namespaces, Eventarc Advanced, Parameter Manager | test them on the memory provider; the emulator runs read settings from Secret Manager on Google Cloud (`kind: secrets`) and leave events off on Azure and Google Cloud |

## Not covered by Halyx

Workflows (Step Functions, Durable Functions, Workflows), email (SES, Communication Services), search, analytics
stores (BigQuery, Synapse), change feeds (DynamoDB Streams, the Cosmos DB change feed), Spanner, Bigtable, Cloud
Tasks and Event Grid basic topics. Propose a portable alternative from the table above where one fits (a scheduled
job publishing messages; a stream instead of a change feed), or the native SDK behind an interface with one
`@Profile`'d implementation per cloud. Say which in the plan.
