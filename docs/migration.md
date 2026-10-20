---
title: Migrating to Halyx
nav_order: 7
---

# Migrating a Spring Boot service to Halyx
{: .no_toc }

Moving a service from a cloud's SDK, Spring Cloud AWS, Spring Cloud Azure or Spring Cloud GCP onto Halyx is mostly
mechanical: each template, client, listener and property has a Halyx equivalent. The parts that need care are
somewhere else:
- data the old code already wrote
- formats other services read
- the weeks a rollout runs old and new versions side by side
- tests that pass for the wrong reason

This page covers the method, the mappings, and those pitfalls, all found in trial migrations.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## The migration skill

The [`halyx-ai-migrate`](ai-skills.md#halyx-ai-migrate) skill runs this method with an AI coding assistant, and
[`halyx-ai-review`](ai-skills.md#halyx-ai-review) checks the result for what still ties it to one cloud. See
[AI skills](ai-skills.md) for installing and running them. Without an assistant, this page is the same method by
hand.

## How a migration runs

1. **Inventory, read only.** Every cloud SDK use, Spring Cloud template, listener, property and config import,
   each with its Halyx equivalent and a risk:
   - **none:** a direct mapping
   - **behaviour:** works, but differently
   - **data:** existing data must stay readable
   - **no equivalent:** stays on the native client

   It also covers what others depend on: cache entries and counters read elsewhere, Kafka record formats, and
   events whose rules and consumers sit outside the service.
2. **Plan, then stop for approval.** The plan sets out:
   - the inventory
   - what stays out of scope
   - whether Halyx reads the existing data as it is stored
   - how old and new instances work together during the rollout
   - the order of the steps
   - the target `halyx.*` properties, with every key checked against the service's own bean names
3. **One area per change,** each built and tested before the next:
   1. dependencies
   2. configuration and secrets
   3. object storage
   4. documents
   5. messaging
   6. events
   7. streams
   8. caches
   9. SQL

   The existing tests keep passing, and memory-provider tests are added.
4. **Optionally, the cloud as a Spring profile,** with a `local` profile on the memory provider, as in the
   [portable microservice](portable-service.md).
5. **A report:** what changed, what behaves differently, and what was left on native clients. Also the follow-ups,
   such as IAM grants and data migrations.

## What maps to what

| Area | Before | After |
|---|---|---|
| Configuration and secrets | `aws-parameterstore:`, `aws-secretsmanager:`, `sm://`, Key Vault and App Configuration property sources | `spring.config.import: halyx:<store>`, `halyx.parameter-stores.<store>` |
| Object storage | `S3Template`, `S3Client`, `BlobContainerClient`, `Storage` | `BlobStore`, `halyx.blob-stores.<key>.bucket` |
| Documents | `DynamoDbTemplate`, `CosmosRepository`, `FirestoreReactiveRepository` | Spring Data `DocumentRepository` or `DocumentStore`, records with `@PartitionKey` and `@SortKey` |
| Messaging | `SqsTemplate`, `SnsTemplate`, `@SqsListener`, `ServiceBusTemplate`, `@ServiceBusListener`, `PubSubTemplate` | `Publisher`, `@HalyxListener` on `halyx.subscriptions` |
| Events | `EventBridgeClient.putEvents`, `EventGridSenderClient`, Eventarc's `PublisherClient` | `EventPublisher` and `CloudEvent`, `halyx.event-buses.<key>` |
| Streams | Spring Kafka with `aws-msk-iam-auth`, Spring Cloud Azure's Event Hubs login, or `GcpLoginCallbackHandler` | the same `KafkaTemplate` and `@KafkaListener`, with `halyx.spring-kafka.cluster` and `halyx.stream-clusters.<key>: { auth: { type: iam } }` |
| Caches | `RedisCacheManager` behind `@Cacheable`; `StringRedisTemplate`; hand-written ElastiCache or Memorystore IAM token providers; Spring Cloud Azure's passwordless Redis | `halyx.cache-manager` behind the same annotations; a `Cache<V>` bean from `halyx.caches.<key>` |
| SQL | the AWS JDBC Wrapper's IAM plugin, Spring Cloud Azure's passwordless JDBC, Spring Cloud GCP's Cloud SQL starter | `halyx.databases.<key>: { auth: { type: iam } }`, a `DataSource` that JPA, `JdbcTemplate` and Flyway use unchanged |

Logins come from each cloud's default chain: IAM roles and EKS Pod Identity, managed identities and Entra Workload
ID, Application Default Credentials and Workload Identity. The hand-written IAM code goes: token signers,
credential providers and client beans. Errors map to Halyx's exceptions (`NotFoundException`,
`PreconditionFailedException` and the rest), not SDK exceptions. Halyx has no equivalent for some services, such as
Kinesis, Spanner, Cosmos DB's change feed, Event Grid basic topics and Datastore. Those stay on the native SDK,
behind one class each.

## Existing data and shared state

| What | Carries over when | Otherwise |
|---|---|---|
| DynamoDB items | string keys in the configured key attributes, and matching attribute types | migrate the keys, or keep the table on the native client |
| Cosmos DB items | the partition key path matches, and `id` is the sort key (a UUID `id` maps with `sort-key: id`) | migrate, or keep the container native |
| Firestore documents | a flat collection keyed by document id | migrate, or keep the collection native |
| Blobs | always: keys and metadata as they are | — |
| Schema and rows | always: Flyway or Liquibase history carries over | — |
| `@Cacheable` entries | never: Spring keys `<cache>::<key>`, Halyx `<cache>:<key>` with its own codec | nothing breaks: old entries are misses and expire. Tools that read entries directly need the new format |
| `Cache` keys and counters | `namespace: ""`, since the namespace defaults to the bean name | new instances count apart from old ones, and lifetime counters restart from zero |
| Kafka consumer offsets | the group ids stay the same | a renamed group rereads from its `auto-offset-reset` position |
| Kafka records | with `KafkaTemplate` and `@KafkaListener` kept, always: the serializers, keys and headers stay Spring's | — |

No store keeps a decimal's scale (`3.50` reads back as `3.5`), so format amounts explicitly. Counters keep no
expiry unless `increment(key, delta, ttl)` sets one; `default-ttl` doesn't apply to them.

## Rollout: old and new instances side by side

A rolling deploy runs both versions at once. Everything they share has to work both ways until the last old
instance stops.

**Events on EventBridge.** Old instances publish the plain detail. Halyx publishes CloudEvents, so the old detail
ends up under `detail.data`; `Source` and `DetailType` are unchanged. Change the rules **before** deploying:

```json
{ "source": ["acme.loyalty"], "detail-type": ["TierChanged"],
  "$or": [ { "detail": { "tier": ["GOLD"] } }, { "detail": { "data": { "tier": ["GOLD"] } } } ] }
```

Halyx consumers read both formats: a plain event is decoded with `detail` as the data. A consumer outside the
service that reads `detail.tier` keeps today's envelope with two rules:
- **Old events:** `"detail": { "specversion": [ { "exists": false } ] }`, delivered as they are.
- **CloudEvents:** `{ "exists": true }`, through an input transformer that rebuilds the old shape from
  `$.detail.data.*`.

One transformer for both formats doesn't work: on old events its paths are missing, and the result isn't valid
JSON. Remove the old halves after the rollout.

**Event Grid namespaces and Eventarc Advanced** carry CloudEvents on both sides already. Keep the type, source,
subject and data, and subscribers see no change.

**Counters, rate limits and locks** share keys through `namespace: ""`. **Kafka** keeps its topics, formats and
groups. **`@Cacheable`** entries aren't shared between the versions; each version fills its own.

## While both libraries are present

The old library stays until its last use is gone, and the two get in each other's way:

- **SDK versions.** The Spring Cloud BOMs pin older SDKs than Halyx needs:
  - AWS SDK 2.31 against Halyx's 2.55
  - `azure-sdk-bom` 1.2.33 against 1.3.8
  - Google Cloud `libraries-bom` 26.83.0 against 26.90.0

  Import the SDK's BOM at Halyx's version first.
- **Auto-configuration.** A provider module brings its cloud's SDK clients, and the old library's
  auto-configuration switches on for each one it finds, even for services the code never used. With Spring Cloud
  GCP, startup failed:
  - for Firestore, Pub/Sub, Storage, Secret Manager and Parameter Manager
  - and for Cloud SQL, through the connector Halyx needs

  Turn those off (`spring.cloud.gcp.<service>.enabled: false`) until Spring Cloud GCP is removed.
- **Test-scoped SDK artifacts.** Re-declaring, say, `software.amazon.awssdk:sqs` with `<scope>test</scope>` for
  the tests' own clients makes `halyx-aws`'s SQS client test-only. Every test passes; the packaged service fails at
  startup with `NoClassDefFoundError`. Let the tests use the clients the provider module brings, and check
  `mvn dependency:list -DincludeScope=runtime`.
- **Bean names.** Every `halyx.*` key is a bean name. A class `Bookings` and `halyx.databases.bookings` clash, and
  startup fails, naming both.
- **Azure identities.** Halyx's default chain takes a user-assigned identity's client id from `AZURE_CLIENT_ID`.
  Spring Cloud Azure's `credential.client-id` therefore moves into the deployment's environment. Workload ID on AKS
  sets it already.
- **JDK cache codec.** Like Spring's default serializer, it reads any class unless `trusted-packages` is set. Set
  it.

## Testing a migration

| Test | Why |
|---|---|
| The existing tests, assertions unchanged | they describe the behaviour to keep |
| Containers wired through `halyx.*` properties | `@ServiceConnection` configures Boot's beans, not Halyx's; left in place, Halyx would use the production hosts from `application.yml` |
| A memory-provider or `local` profile test | no cloud, no emulators. The memory provider has no SQL, and Spring for Apache Kafka can't use its in-process streams: `local` uses Boot's `spring.datasource` and `spring.kafka`, and startup says so if not. Its event buses have no rules, so consumers relying on a filter see everything |
| Services with no emulator | Event Grid namespaces and Eventarc Advanced: publish to a memory-provider bus and read it back through a subscription, rather than mocking the SDK |
| The old service's state | starts the migrated service on what the old one left behind: schema history, Spring's cache entries, counters, committed offsets (checked directly), old-format events |
| Deliberate mistakes | break one compatibility setting per area and see the test fail. The trials caught a renamed consumer group only once the test read the committed offset: the idempotent handler hid the replay |
| The production profile | what the cloud profile resolves to, bound to Halyx's properties, without connecting anywhere: IAM logins everywhere, the old settings gone |
| The packaged service | start the jar or image once: the test classpath can hold what the package lacks |
| Real clouds | the IAM logins and the services with no emulator, once per phase |

## Trials

Six Spring Cloud services were migrated step by step: two per cloud, between them using every kind of service
Halyx covers.

| Before | Services | Emulators and containers |
|---|---|---|
| Spring Cloud AWS 3.4 | S3, DynamoDB (`DynamoDbTemplate`), SNS, SQS (`@SqsListener`), Parameter Store | 5/5 |
| Spring Cloud AWS 3.4 and the AWS SDK | EventBridge to SQS; MSK, ElastiCache and RDS with IAM (`aws-msk-iam-auth`, a hand-signed token, the AWS JDBC Wrapper); `KafkaTemplate`, `@Cacheable`, JPA and Flyway | 14/14 |
| Spring Cloud Azure 5.22 | Blob Storage, Cosmos DB (`CosmosRepository`), Service Bus (`ServiceBusTemplate`, `@ServiceBusListener`) | 5/5 |
| Spring Cloud Azure 5.22 and the Azure SDK | Event Grid namespaces; passwordless Event Hubs (Kafka), Azure Managed Redis and PostgreSQL | 12/12 |
| Spring Cloud GCP 7.4 | Cloud Storage, Firestore (`FirestoreReactiveRepository`), Pub/Sub (`PubSubTemplate`) | 5/5 |
| Spring Cloud GCP 7.4 and the Google Cloud SDK | Eventarc Advanced; Managed Kafka, Memorystore and Cloud SQL with IAM | 11/11 |

In each, the original tests passed with their assertions unchanged. The exceptions were the event checks that had
read a mocked SDK client: they now read the Halyx bus and check the same values. The data the old library had
written read correctly through Halyx.

The second trial on each cloud then ran on a throwaway cluster, logging in with the platform's workload identity.
It was driven over HTTP, and the test machine checked what subscribers outside the service receive:

| Cluster | Services | Result |
|---|---|---|
| AKS, Entra Workload ID | Event Hubs' Kafka endpoint, Azure Managed Redis, Azure Database for PostgreSQL, an Event Grid namespace topic read by a pull subscriber | 4/4 |
| GKE, Workload Identity | Managed Service for Apache Kafka, Memorystore (IAM, TLS with its private CA), Cloud SQL through the connector, an Eventarc Advanced bus read through its pipeline's Pub/Sub subscription | 4/4 |
| EKS, Pod Identity | EventBridge rules (`$or`, `exists`, an input transformer) to SQS, read by the CRM queue and by old-format events from the test machine; RDS and ElastiCache with IAM; Kafka in the cluster, as MSK isn't available on the test account | 5/5, after a first run failed at startup: the test-scoped SDK artifacts above |

The trials found one Halyx bug, now fixed: next to another Spring Data module, both claimed every repository. They
also made two startup errors clearer: `halyx.databases` and `halyx.spring-kafka` on the memory provider now say what
to use instead.
