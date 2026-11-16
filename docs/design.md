---
title: Architecture and design
nav_order: 9
---

# Architecture and design
{: .no_toc }

How Halyx is put together: the split between the portable API and each cloud's driver, the patterns and
principles behind it, and the modules. [Build and test](build-and-test.md) covers building it, the conformance kits
and the benchmarks.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Architecture

Every service is split into a portable **API** that applications use, and an **SPI** that each cloud
implements. The API does validation, capability checks, interceptors and error translation once, for
every provider:

```
 application ──► BlobStore (API)                                        halyx-core
                   │ DefaultBlobStore: validation → capability check →
                   │ interceptor chain → driver call → error translation
                   ▼
                 BlobStoreDriver (SPI)
       ┌───────────┼──────────────────┬──────────────────┐
  S3BlobStoreDriver  AzureBlobStoreDriver  GcsBlobStoreDriver  InMemoryBlobStoreDriver
   halyx-aws          halyx-azure          halyx-gcp          halyx-core
```

The same split repeats for every service: `DocumentStore` and `DocumentStoreDriver`, `Publisher` and
`PublisherDriver`, `StreamProducer` and `ProducerDriver`, `Cache` and `CacheDriver`, `ParameterStore` and
`ParameterDriver`, `KeyStore` and `KeyStoreDriver`. Providers are
discovered with `ServiceLoader`, so `halyx-core` has no cloud SDK dependency, and the build enforces
that.

## Patterns

| Pattern | Where |
|---|---|
| Bridge | each API (`BlobStore`, `DefaultBlobStore`; `FunctionInvoker`, `DefaultFunctionInvoker`; `KeyStore` and `CryptoKey`, `DefaultKeyStore` and `DefaultCryptoKey`) and its SPI (`BlobStoreDriver`, `FunctionInvokerDriver`, `KeyStoreDriver`). The envelope format, caches and local verification live on the API side, so a key driver only wraps, unwraps, signs and makes MACs |
| Plugin, Separated Interface | `CloudProvider` in core, discovered from provider jars through `ServiceLoader` |
| Abstract Factory | `ProviderConnection` creates the family of drivers for one account and region |
| Registry, Facade | `Halyx` holds the named `CloudContext`s |
| Gateway, Adapter | each driver wraps a native SDK client |
| Template Method | an `Abstract*Driver` base per SPI, which every driver extends: blob stores, document stores, publishers and subscriptions, event publishers, stream producers and consumers, function invokers, caches, parameter stores and key stores (translation fallback, `unwrap`) |
| Chain of Responsibility | `Interceptor` and `InterceptorChain` (`RetryInterceptor` included) |
| Value Object | `BlobKey`, `ETag`, `Key`, `Version`, `ProviderConfig`, `SealedData`, `Signature`: immutable |
| Service Stub | the `memory` provider, for tests, with `InMemoryFunctions` serving registered handlers and `InMemoryKeys` holding real JDK keys |
| Exception Translation | the sealed `CloudException` hierarchy with `isRetryable()`; HTTP statuses through `AzureErrors.fromStatus` and `GcpErrors.fromStatus`, shared by the SDK translations and the HTTP function drivers; what a failed function reported kept whole as a `FunctionError` cause |

## Principles

- **Portable semantics, not just portable signatures.** Listing order, idempotent delete, conditional
  writes, versions and expiry mean the same on every provider, and the conformance kits check it.
- **Declare differences instead of hiding them.** Optional features are capabilities. Asking for a
  missing one fails rather than being ignored, and `unwrap` reaches the native client.
- **No hidden waits.** Halyx doesn't block your call to paper over eventual consistency or
  asynchronous deletes. It fails fast with a retryable error, and you choose how to retry.
- **Infrastructure is yours.** Halyx never creates buckets, tables, topics, databases or keys, and never rotates or
  deletes keys.
- **Fit the platform.** Java 21 virtual threads for parallel transfers and listeners, pooled buffers for
  part uploads, shared HTTP clients per provider connection, and SDK-native retry modes.

## Modules

| Module | Purpose |
|---|---|
| `halyx-bom` | Import to align Halyx versions |
| `halyx-core` | portable API, provider SPI, error model, interceptors, in-memory provider, the HLXS envelope format and the encrypted views over blob stores, document stores and parameter stores. No cloud SDKs (enforced) |
| `halyx-tck` | the conformance test kits every provider must pass |
| `halyx-redis` | the shared Redis and Valkey cache driver (Lettuce), used by every provider |
| `halyx-sql` | the shared HikariCP pool with per-connection credentials (passwords, cloud IAM tokens) |
| `halyx-r2dbc` | pooled R2DBC connections with the same password and cloud IAM logins, for reactive code and Spring Data R2DBC |
| `halyx-reactor` | Project Reactor views of every service on the application's scheduler: `Mono` and `Flux`, streams in and out, subscriptions and stream consumers as a `Flux` of deliveries |
| `halyx-micrometer` | Micrometer observations of every operation, and trace context through messages, events, stream records and function invocations, for any application; the starter applies them |
| `halyx-kafka` | the shared Kafka stream driver, with password and cloud IAM login (SASL `OAUTHBEARER`), used by every provider |
| `halyx-aws` | AWS SDK v2: S3, DynamoDB, SNS, SQS, EventBridge, Lambda, SSM Parameter Store, Secrets Manager, KMS, ElastiCache and RDS IAM login |
| `halyx-azure` | Azure SDK: Blob Storage, Cosmos DB, Service Bus, Event Grid namespaces, Azure Functions, Key Vault secrets and keys, App Configuration, Entra ID login for caches and databases |
| `halyx-gcp` | Google Cloud: Cloud Storage, Firestore, Pub/Sub, Eventarc Advanced, Cloud Run functions, Secret Manager, Parameter Manager, Cloud KMS, Memorystore and Cloud SQL IAM login |
| `halyx-vault` | HashiCorp Vault KV v2 parameters and secrets, and Transit keys |
| `halyx-spring-data-document` | Spring Data repositories over document stores: records as entities, derived queries on keys and indexes, optimistic locking, keyset scrolling |
| `halyx-spring-boot-autoconfigure` | Spring Boot auto-configuration, properties, health and observations |
| `halyx-spring-boot-starter` | the dependency Spring Boot applications add |
| `halyx-reactor-tests` | the conformance kits through `halyx-reactor`'s adapters, on the memory provider and on Kafka and Valkey (not published) |
| `halyx-spring-boot4-tests` | the shared Spring and Spring Data tests run on Boot 4 (not published) |
| `halyx-benchmarks` | JMH benchmarks and a load-test harness (not published) |
| `halyx-samples` | `portable-orders`, a Spring Boot 4 service on every Halyx API, and `portable-orders-boot3`, the same on Boot 3.5, with their end-to-end tests (not published); see [Portable microservice](portable-service.md) |
