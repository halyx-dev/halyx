---
title: Home
nav_order: 1
description: "Halyx: one Java API for storage, NoSQL, messaging, streams, SQL, caches and secrets on AWS, Azure and Google Cloud — a portable library over the clouds' own SDKs."
permalink: /
---

# Halyx
{: .fs-9 }

One Java API for AWS, Azure and Google Cloud. Halyx gives Java 21 microservices one consistent way to use cloud services (object storage, NoSQL,
messaging, streams, SQL, caches, parameters and secrets) on AWS, Azure and Google Cloud. The services keep their
own SDKs and strengths; your code stops depending on them.
{: .fs-6 .fw-300 }

[Get started](getting-started.md){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[API guide](api/index.md){: .btn .fs-5 .mb-4 .mb-md-0 .mr-2 }
[Spring Boot](spring-boot.md){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## Write it once, run it on any cloud

```java
try (Halyx halyx = Halyx.builder()
        .register("aws", ProviderConfig.of("aws").region("eu-west-2"))
        .register("gcp", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, "acme-prod"))
        .build()) {

    BlobStore invoices = halyx.provider("aws").blobStore("acme-invoices");     // Amazon S3
    Publisher events = halyx.provider("gcp").publisher("order-events");       // Google Cloud Pub/Sub

    invoices.put(BlobKey.of("2026/42.pdf"), BlobContent.ofFile(pdf));
    events.publish(OutgoingMessage.builder(json).attribute("type", "invoiced").build());
}
```

Moving the invoices to Azure Blob Storage is a change of provider name, not a rewrite. Errors, retries,
conditional writes, versions and expiry behave the same on every cloud, and a shared conformance kit
checks that for every provider, against emulators on every build and
[against the real clouds](cloud-testing/index.md).

## Services

| Service | Halyx API | AWS | Azure | Google Cloud | Also |
|---|---|---|---|---|---|
| [Object storage](api/storage.md) | `BlobStore` | Amazon S3 | Azure Blob Storage | Cloud Storage | in-memory |
| [NoSQL documents](api/nosql.md) | `DocumentStore` | DynamoDB | Cosmos DB for NoSQL | Firestore | in-memory |
| [Messaging](api/messaging.md) | `Publisher`, `Subscription` | SNS, SQS | Service Bus | Pub/Sub | in-memory |
| [Events](api/events.md) | `CloudEvent`, `EventPublisher` | EventBridge | Event Grid namespaces | Eventarc Advanced | in-memory |
| [Streams](api/streams.md) | `StreamProducer`, `StreamConsumer` | MSK | Event Hubs (Kafka endpoint) | Managed Service for Apache Kafka | any Kafka cluster, in-memory |
| [Functions](api/functions.md) | `FunctionInvoker` | Lambda | Azure Functions | Cloud Run functions | in-memory |
| [SQL databases](api/sql.md) | `Database` (a JDBC `DataSource`) | RDS, Aurora | Azure Database for PostgreSQL and MySQL, Azure SQL | Cloud SQL | any PostgreSQL, MySQL or SQL Server |
| [Caches](api/caches.md) | `Cache` | ElastiCache | Azure Cache for Redis, Azure Managed Redis | Memorystore | any Redis or Valkey server, in-memory |
| [Parameters and secrets](api/parameters.md) | `ParameterStore` | SSM Parameter Store, Secrets Manager | App Configuration, Key Vault | Parameter Manager, Secret Manager | HashiCorp Vault, in-memory |

## Why teams use it

Portable code
: The same API, error types and semantics on every cloud. Services move between providers by
  configuration.

Several clouds in one service
: Each cloud is a named provider in one `Halyx` object, so one microservice can read from S3, publish
  to Pub/Sub and cache in Azure without a second SDK or a second set of error handling.

No lowest common denominator
: Differences are declared as [capabilities](api/capabilities-and-unwrap.md) rather than hidden, and
  `unwrap` hands you the native SDK client for anything provider-specific.

Passwordless by default
: Databases, caches and streams log in with the cloud's identity (AWS IAM, Microsoft Entra ID, Google IAM), with
  tokens refreshed before they expire.

Production behaviour built in
: Parallel transfers, batching, checksums, presigned URLs, claim checks, listener concurrency,
  fail-open caches, and observability through Micrometer.

Spring Boot ready
: A starter for Boot 3.5 and 4: beans from `halyx.*` properties, `@HalyxListener`, health checks,
  metrics, tracing and `spring.config.import` for parameters and secrets.

## Where to go next

- [Getting started](getting-started.md): add the dependencies and make your first calls.
- [API guide](api/index.md): every service, with examples, error handling and native-client access.
- [Provider configuration](providers.md): credentials, regions, endpoints and every provider setting.
- [Spring Boot](spring-boot.md): the starter, its properties, beans and integrations.
- [Design, build and test](design.md): how Halyx is built, tested and benchmarked.
- [Cloud testing](cloud-testing/index.md): results of the conformance kits on real AWS, Azure and Google Cloud.
- [Roadmap](roadmap.md): what's done and what's next.
