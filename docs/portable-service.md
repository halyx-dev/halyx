---
title: Portable microservice
nav_order: 6
---

# A portable Spring Boot microservice
{: .no_toc }

`halyx-samples/portable-orders` is an orders service that uses every Halyx API. It's built once, as one jar and one
container image, and runs unchanged on AWS, Azure and Google Cloud. Spring profiles and properties pick the cloud;
the code has no cloud-specific branches. The same end-to-end tests run against every deployment.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## What it does

```
POST /customers/{id}/orders ──► orders (DocumentStore, via a Spring Data repository)
                            ──► order-placed (Publisher) ──► fulfilment (@HalyxListener)
                            │                                  ├─► receipts (BlobStore), signed with a key from secrets (ParameterStore)
                            │                                  ├─► ledger (DataSource, JdbcTemplate)           sql
                            │                                  └─► status CONFIRMED (DocumentStore.update)
                            ├─► order-events (EventPublisher) ──► notifications (@HalyxListener, CloudEvent)   events
                            ├─► order-audit (StreamProducer)  ──► auditor (@HalyxListener, stream consumer)    streams
                            └─► idempotency (Cache): an Idempotency-Key header makes retries safe            cache
spring.config.import: halyx:settings  ──► orders.max-lines, orders.currency (ParameterStore, reloaded every minute)
```

| Endpoint | Returns |
|---|---|
| `POST /customers/{customerId}/orders` | `201` with the new order; `200` with the earlier order for a repeated `Idempotency-Key`; `400` for an invalid order |
| `GET /customers/{customerId}/orders` | the customer's orders, newest first |
| `GET /customers/{customerId}/orders/{orderId}` | the order: its status, and whether it has been receipted, notified and audited |
| `GET /customers/{customerId}/orders/{orderId}/receipt` | the signed receipt, once fulfilment has written it |
| `GET /customers/{customerId}/ledger` | the customer's ledger entries (`sql` only) |
| `GET /deployment` | the cloud, the features on, the provider behind each Halyx bean, and the settings |
| `GET /actuator/health` | Boot's health, with Halyx's indicators |

## Choosing the cloud and the features

One profile names the cloud, and up to four more turn on optional features:

| Profile | Adds |
|---|---|
| `local` | every service in memory: no cloud, no containers |
| `aws`, `azure` or `gcp` | the cloud's provider, named after it, and the settings import |
| `events` | an event bus and the subscription its router delivers to |
| `streams` | a Kafka stream cluster, a producer and a consumer group |
| `cache` | a Redis or Valkey cache for idempotency keys |
| `sql` | a PostgreSQL ledger, with IAM login and `schema.sql` |

```bash
SPRING_PROFILES_ACTIVE=aws,events,cache,sql java -jar portable-orders.jar
docker run -e SPRING_PROFILES_ACTIVE=gcp,events,streams,cache,sql -e GOOGLE_CLOUD_PROJECT=acme halyx/portable-orders:1.0.0-SNAPSHOT
```

Each feature is a document in `application.yml`, gated with `spring.config.activate.on-profile`, and its components
are `@Profile` beans. A cloud and a feature together, such as `azure & events`, add what only that pair needs, such as
the Event Grid endpoint. Features stay optional because not every deployment can offer every service: MSK isn't
available on the AWS test account, and Event Grid and Eventarc have no emulators.

Resource names come from `ORDERS_*` environment variables, with defaults for a deployment that uses the sample's
names:

| Variable | Used for | Default |
|---|---|---|
| `ORDERS_RECEIPTS_BUCKET` | the S3 bucket, Blob Storage container or Cloud Storage bucket | `portable-orders-receipts` |
| `ORDERS_TABLE` | the DynamoDB table, Cosmos DB container or Firestore collection (keys `pk` and `sk`) | `portable-orders` |
| `ORDERS_TOPIC`, `ORDERS_FULFILMENT_SUBSCRIPTION` | the SNS topic and SQS queue, Service Bus topic and subscription, or Pub/Sub topic and subscription | `portable-orders-placed`, `portable-orders-fulfilment` (`portable-orders-placed/subscriptions/fulfilment` on Azure) |
| `ORDERS_EVENT_BUS`, `ORDERS_NOTIFICATIONS_SUBSCRIPTION` | the event bus, and the queue or subscription its rule delivers to | `portable-orders`, `portable-orders-notifications` (`notifications` on Azure) |
| `ORDERS_KAFKA_BOOTSTRAP_SERVERS`, `ORDERS_KAFKA_AUTH`, `ORDERS_AUDIT_TOPIC` | the stream cluster, its login (`iam` by default) and topic | `portable-orders-audit` |
| `ORDERS_CACHE_ENDPOINT`, `ORDERS_CACHE_AUTH` | the cache, and its login (`iam` by default) | |
| `ORDERS_DB_HOST`, `ORDERS_DB_PORT`, `ORDERS_DB_NAME`, `ORDERS_DB_USER`, `ORDERS_DB_AUTH` | the ledger database, with IAM login by default | port `5432`, database `orders`, user `portable_orders` |
| `AWS_REGION` | the AWS region | `eu-west-2` |
| `ORDERS_STORAGE_ACCOUNT`, `ORDERS_COSMOS_ENDPOINT`, `ORDERS_SERVICEBUS_NAMESPACE`, `ORDERS_KEYVAULT_URL`, `ORDERS_APPCONFIG_ENDPOINT`, `ORDERS_EVENTGRID_ENDPOINT` | the Azure accounts and endpoints | |
| `GOOGLE_CLOUD_PROJECT`, `ORDERS_PARAMETER_LOCATION` | the Google Cloud project, and the Parameter Manager location | location `global` |

The settings (`orders/max-lines` and `orders/currency`) and the `receipt-signing-key` secret live under the
`portable-orders` prefix: in SSM Parameter Store and Secrets Manager, App Configuration and Key Vault, or Parameter
Manager and Secret Manager.

## What portability takes

The service's code uses Halyx' interfaces and Spring's, and nothing from a cloud SDK. What differs between clouds is
all configuration:

| Difference | Where it's handled |
|---|---|
| Resource naming, such as a Service Bus subscription's `<topic>/subscriptions/<name>` | the cloud's document in `application.yml` |
| Account endpoints on Azure; the project on Google Cloud | the provider's `properties` |
| Login: IAM roles, Entra ID or Google service accounts | the default credential chain, or `credentials.*`; nothing in the code |
| Cache login details: the ElastiCache cache name, the Entra ID object id | `aws & cache` and `azure & cache` documents |
| Numbers: DynamoDB, Cosmos DB and Firestore don't keep a decimal's scale, so `3.50` reads back as `3.5` | the receipt formats amounts itself |
| Bean names: a `halyx.*` key is a bean name, so a class called `Fulfilment` can't sit next to `halyx.subscriptions.fulfilment` | Halyx fails at startup, naming both; the sample's classes are `OrderFulfilment`, `ReceiptService` and so on |

## Migrating an existing service

The repository has a Claude Code skill for moving an existing Spring Boot service onto Halyx:
`.claude/skills/migrate-to-halyx`. It works from Spring Cloud AWS, Spring Cloud Azure, Spring Cloud GCP or the raw SDKs.
Copy the folder into the service's repository (or `~/.claude/skills`) and ask Claude Code to migrate the service,
or run `/migrate-to-halyx`. The skill works in phases:

1. An inventory of every cloud SDK, template, listener, property and config import, each with its Halyx
   equivalent and a risk: none, behaviour, data, or no equivalent.
2. A plan, including whether Halyx can read the existing data as it's stored. **The skill stops here for approval.**
3. One area per change (dependencies, configuration, storage, documents, messaging, events, streams, caches and
   SQL), with the existing tests kept passing and memory-provider tests added.
4. Optionally, the cloud as a profile, as in this sample.
5. A report of what changed, what behaves differently, and what was left on native clients.

Its references hold the mappings for each cloud, the differences that matter (decimals, ordering, delays, event
formats, data layouts, the permissions Halyx's health checks need), how old and new instances work side by side
during a rollout, and the test patterns. It was tried on six services, two per cloud, each migrated step by step on
emulators and containers:

| Before | Services | Result |
|---|---|---|
| Spring Cloud AWS 3.4 | S3, DynamoDB (`DynamoDbTemplate`), SNS, SQS (`@SqsListener`), Parameter Store | 5/5 |
| Spring Cloud AWS 3.4 and the AWS SDK | EventBridge to SQS, MSK, ElastiCache and RDS with IAM (`aws-msk-iam-auth`, a hand-signed token, the AWS JDBC Wrapper); `KafkaTemplate`, `@Cacheable`, JPA and Flyway | 14/14 |
| Spring Cloud Azure 5.22 | Blob Storage, Cosmos DB (`CosmosRepository`), Service Bus (`ServiceBusTemplate`, `@ServiceBusListener`) | 5/5 |
| Spring Cloud Azure 5.22 and the Azure SDK | Event Grid namespaces, and passwordless Event Hubs (Kafka), Azure Managed Redis and PostgreSQL | 12/12 |
| Spring Cloud GCP 7.4 | Cloud Storage, Firestore (`FirestoreReactiveRepository`), Pub/Sub (`PubSubTemplate`) | 5/5 |
| Spring Cloud GCP 7.4 and the Google Cloud SDK | Eventarc Advanced, and Managed Kafka, Memorystore and Cloud SQL with IAM | 11/11 |

In each, the original tests passed with their assertions unchanged. The two exceptions were event checks: they had
read a mocked SDK client, and now read the Halyx bus (the same values through Halyx's `CloudEvent` accessors).
Every trial also added three tests:
- a memory-provider (or `local` profile) test
- a test of the production profile, checking what it resolves to
- a test starting the migrated service on what the old one left behind

The old service's state covered DynamoDB items, Cosmos DB items with UUID ids and Firestore documents, plus Flyway
history, Spring's cache entries, rate and lifetime counters, committed Kafka offsets, and old-format events from
instances still being replaced. Each compatibility setting was also broken on purpose to show the tests notice.

The trials found one Halyx bug, now fixed: next to another Spring Data module, both claimed every repository. They
also gave two startup errors clearer messages: `halyx.databases` and `halyx.spring-kafka` on the memory provider now
say what to use instead.

## On Kubernetes: EKS, GKE and AKS

The Helm chart in `halyx-samples/portable-orders/deploy/helm/portable-orders` deploys the same image on any cluster.
Its values carry what differs between clouds:

| Value | Holds |
|---|---|
| `profiles` | the cloud and the features, such as `[gcp, events, streams, cache, sql]` |
| `env` | the `ORDERS_*` resource names, `AWS_REGION` or `GOOGLE_CLOUD_PROJECT` |
| `config` | more Spring configuration as YAML, mounted as an extra `application.yml`: settings an environment variable can't express, such as provider `properties` |
| `files` | files mounted under `/etc/portable-orders/files`, such as a cache's private CA |
| `serviceAccount.annotations`, `podLabels` | the binding to the cloud's workload identity |
| `javaToolOptions` | JVM options; by default `-XX:MaxDirectMemorySize=64M`, since the buildpack's memory calculator otherwise caps direct memory at 10 MB, too little for the cloud SDKs' Netty clients |

The pods log in with the platform's workload identity. There are no keys in the cluster, and the code and
configuration don't name the mechanism: each cloud's default credential chain finds it.

| Cluster | Workload identity | What the chart sets |
|---|---|---|
| EKS | EKS Pod Identity: an association of the service account with an IAM role | nothing; the association names the namespace and service account |
| GKE | Workload Identity Federation for GKE: the Kubernetes service account acts as a Google service account | `iam.gke.io/gcp-service-account: <email>` |
| AKS | Microsoft Entra Workload ID: a managed identity federated to the service account | `azure.workload.identity/client-id: <client id>`, and the pod label `azure.workload.identity/use: "true"` |

`cloud-testing/scripts/build-portable-orders-images.sh` builds the Spring Boot 4 and 3.5 images once.
`cloud-testing/scripts/eks-real-cloud.sh`, `gke-real-cloud.sh` and `aks-real-cloud.sh` each create a throwaway
cluster, a registry and every service the sample uses, all named after the run. Each script pushes those same two
images and deploys them in turn, and checks that its pods run the digests it pushed. It then runs `DeployedServiceIT` against each through a port-forward, and
always tears everything down. The latest runs are in
[Cloud testing](cloud-testing/index.md#one-image-on-three-clusters). The services are reached the way production would
reach them:

| Service | EKS | GKE | AKS |
|---|---|---|---|
| Cache | ElastiCache, inside the VPC, IAM user | Memorystore, inside the VPC through Private Service Connect, IAM and TLS with its private CA | Azure Managed Redis, Entra ID |
| SQL | RDS, inside the VPC, IAM login | Cloud SQL through the Cloud SQL connector, IAM login | Azure Database for PostgreSQL, the identity as its Entra admin |
| Streams | off: MSK isn't available on the test account | Managed Service for Apache Kafka, inside the VPC | Event Hubs' Kafka endpoint, Entra ID |
| Settings, secrets | SSM Parameter Store, Secrets Manager | Parameter Manager, Secret Manager | App Configuration, Key Vault |

On Google Cloud, the Cloud SQL connector needs `com.google.cloud.sql:postgres-socket-factory` on the classpath; the
sample declares it at runtime scope, as an application would.

## Testing

`PortableOrdersSuite` holds the end-to-end tests. They use only the HTTP API, so they run unchanged against every
deployment. Each deployment states what it expects, such as its cloud, its features, the settings it seeded and its
signing key, and the suite checks the service's `/deployment` report against that. Tests of features that are off are
skipped, so the report shows what each deployment ran.

| Deployment | Test | Services |
|---|---|---|
| `local` profile, in process | `LocalDeploymentTest` | the memory provider; every feature but SQL |
| AWS emulators, in process | `AwsEmulatorIT` | Floci (S3, DynamoDB, SNS, SQS, EventBridge, SSM, Secrets Manager), PostgreSQL, Valkey, Kafka; every feature |
| Azure emulators, in process | `AzureEmulatorIT` | Floci-az (Blob Storage, Cosmos DB, Key Vault, App Configuration), the Service Bus emulator, PostgreSQL, Valkey, Kafka; all but `events` |
| Google Cloud emulators, in process | `GcpEmulatorIT` | Floci-gcp (Cloud Storage, Firestore, Pub/Sub, Secret Manager), PostgreSQL, Valkey, Kafka; all but `events` |
| the image, on the AWS emulators | `ImageIT`, with `-Pimage` | as `AwsEmulatorIT`, with the image configured only through its environment |
| a running deployment | `DeployedServiceIT`, with `-Dorders.e2e.url=...` | whatever it runs on: the image on EKS, GKE and AKS, through the scripts above |

`portable-orders-boot3` builds the same sources and runs the same tests on Spring Boot 3.5. The sample itself
targets Boot 4.

```bash
mvn -pl halyx-samples/portable-orders,halyx-samples/portable-orders-boot3 -am verify   # local and emulators, Boot 4 and 3.5
mvn -pl halyx-samples/portable-orders -Pimage verify                                   # also builds the image and tests it
```

The emulator runs differ from the real clouds in two places. Event Grid namespaces and Eventarc Advanced have no
emulators, so `events` is off on the Azure and Google Cloud emulators. Parameter Manager has none either, so the
Google Cloud emulator run reads its settings from Secret Manager (`halyx.parameter-stores.settings.kind=secrets`). The
latest results are in [Cloud testing](cloud-testing/index.md#portable-microservice).
