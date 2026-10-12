---
title: Capabilities and native clients
parent: API guide
nav_order: 9
---

# Capabilities and native clients
{: .no_toc }

Halyx doesn't reduce every cloud to the lowest common denominator. Where providers differ, the
differences are **declared** as capabilities, and anything provider-specific is one `unwrap` away.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Capabilities

Services that have optional features expose `capabilities()`. Asking for a feature the provider doesn't
have fails with `UnsupportedFeatureException`, rather than being silently ignored:

```java
if (store.capabilities().supports(StorageCapability.PRESIGNED_URL)) {
    return store.presignDownload(key, Duration.ofMinutes(10)).uri();
}
return proxyDownload(key);

Publisher events = halyx.provider("gcp").publisher("orders");
events.capabilities().require(MessagingCapability.DEDUPLICATION, events.provider());   // fails on Pub/Sub
```

`capabilities().asSet()` lists them all. Capabilities reflect the configured credentials as well as the
provider: GCS can presign only with credentials that can sign.

### Storage

| `StorageCapability` | AWS | Azure | Google Cloud |
|---|---|---|---|
| `CONDITIONAL_WRITE`, `CONDITIONAL_DELETE` | yes | yes | yes |
| `RANGE_READ`, `SERVER_SIDE_COPY`, `USER_METADATA` | yes | yes | yes |
| `CHECKSUM`, `BATCH_DELETE`, `STORAGE_TIERS` | yes | yes | yes |
| `PRESIGNED_URL`, `PRESIGNED_MULTIPART` | yes | yes | with signing credentials |

### Messaging

| `MessagingCapability` | AWS | Azure | Google Cloud |
|---|---|---|---|
| `ORDERING` | FIFO topics and queues | session-enabled entities | ordering-enabled subscriptions |
| `DEDUPLICATION` | FIFO topics and queues | duplicate-detection entities | no |
| `DELAYED_DELIVERY` | SQS queues | yes | no |
| `NACK_DELAY` | yes | no (redelivers at once) | yes |

### Streams

| `StreamCapability` | AWS | Azure | Google Cloud |
|---|---|---|---|
| `IDEMPOTENCE` | yes | no | yes |
| `TRANSACTIONS` | yes | no | yes |

### Parameters and secrets

| `ParameterCapability` | Supported by |
|---|---|
| `VERSIONS` | SSM Parameter Store, Secrets Manager, Key Vault, Secret Manager, Parameter Manager, Vault. Not App Configuration. |

## Native clients with `unwrap`

Every service except `Database` can hand you the native SDK client it uses, already configured with the
provider's region, credentials and HTTP settings:

```java
S3Client s3 = store.unwrap(S3Client.class);
s3.putBucketTagging(b -> b.bucket(store.bucket()).tagging(t -> t.tagSet(tags)));
```

`unwrap` throws `IllegalArgumentException` if the provider has no client of that type, so code written
for one cloud fails clearly on another instead of misbehaving. Prefer the portable API, and keep
`unwrap` calls in one place, behind your own interface, so the rest of your code stays portable.

| Service | AWS | Azure | Google Cloud |
|---|---|---|---|
| `BlobStore` | `S3Client`, `S3Presigner` | `BlobContainerClient`, `BlobServiceClient` | `Storage` |
| `DocumentStore` | `DynamoDbClient` | `CosmosContainer` | `Firestore` |
| `Publisher` | `SnsClient` (`SqsClient` with `aws.target=sqs`) | `ServiceBusSenderClient` | `TopicAdminClient` |
| `Subscription` | `SqsClient` | `ServiceBusReceiverClient` (`ServiceBusSessionReceiverClient` for session entities) | `SubscriptionAdminClient` |
| `ParameterStore` | `SsmClient`, `SecretsManagerClient` | `ConfigurationClient`, `SecretClient` | `ParameterManagerClient`, `SecretManagerServiceClient` |
| `StreamProducer` | Kafka's `Producer` | (same) | (same) |
| `StreamConsumer` | Kafka's `Consumer`, used only from the polling thread | (same) | (same) |
| `Cache` | Lettuce `StatefulRedisConnection` / `StatefulRedisClusterConnection`, `RedisClient` / `RedisClusterClient` | (same) | (same) |

HashiCorp Vault parameter stores unwrap to `VaultClient`, Halyx's own client. A `Database` has no
`unwrap`: its `dataSource()` is the HikariCP pool, which JDBC's own `DataSource.unwrap(HikariDataSource.class)`
reaches.

## When to reach for `unwrap`

Good uses:
- bucket or table administration: tags, lifecycle rules, CORS
- data structures beyond strings on Redis
- provider features not in the portable API yet, such as S3 Object Lock or Cosmos DB change feed

If you find yourself unwrapping for something common, it may belong in Halyx; see the
[roadmap](../roadmap.md).
