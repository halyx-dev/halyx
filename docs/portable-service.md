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
| a running deployment | `DeployedServiceIT`, with `-Dorders.e2e.url=...` | whatever it runs on |

`portable-orders-boot3` builds the same sources and runs the same tests on Spring Boot 3.5. The sample itself
targets Boot 4.

```bash
mvn -pl halyx-samples/portable-orders,halyx-samples/portable-orders-boot3 -am verify   # local and emulators, Boot 4 and 3.5
mvn -pl halyx-samples/portable-orders -Pimage verify                                   # also builds the image and tests it
```

The emulator runs differ from the real clouds in two places. Event Grid namespaces and Eventarc Advanced have no
emulators, so `events` is off on the Azure and Google Cloud emulators. Parameter Manager has none either, so the
Google Cloud emulator run reads its settings from Secret Manager (`halyx.parameter-stores.settings.kind=secrets`).
