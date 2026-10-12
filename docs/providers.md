---
title: Provider configuration
nav_order: 4
---

# Provider configuration
{: .no_toc }

A provider is one cloud account, project or subscription, in one region, with one set of credentials.
Register as many as you need, each under its own name.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## `ProviderConfig`

```java
ProviderConfig aws = ProviderConfig.of("aws")              // aws | azure | gcp | vault | memory
        .region("eu-west-2")                              // optional: the cloud's default otherwise
        .endpoint(URI.create("http://localhost:4566"))    // optional: an emulator or compatible service
        .credentials(Credentials.defaultChain())          // the default
        .property("http.max-connections", "256");         // provider settings, below

Halyx halyx = Halyx.builder().register("orders", aws).build();
```

In Spring Boot, the same settings live under `halyx.providers.<name>`; see
[Spring Boot](spring-boot.md#providers).

## Credentials

| `Credentials` | AWS | Azure | Google Cloud | HashiCorp Vault |
|---|---|---|---|---|
| `defaultChain()` (default) | the AWS default chain: environment, profile, SSO, web identity (EKS), container and instance roles | `DefaultAzureCredential`: environment, workload identity (AKS), managed identity, Azure CLI | Application Default Credentials: `GOOGLE_APPLICATION_CREDENTIALS`, workload identity (GKE), metadata server, `gcloud` login | the `VAULT_TOKEN` environment variable |
| `of(id, secret)` | access key ID and secret | storage account name and key (shared key, Blob Storage only) | not supported: use the default chain | `of("token", "<token>")` |
| `anonymous()` | unsigned requests, for emulators | no credentials, for emulators | no credentials, for emulators | |

Prefer the default chain. It covers workload identity on Kubernetes, managed identities and CLI logins,
with no secrets in configuration. Passwordless database and cache logins
([SQL](api/sql.md), [caches](api/caches.md)) need the default chain on Azure and Google Cloud.

## HTTP connections

Each provider connection shares one pooled HTTP client across its services: S3, SSM and Secrets Manager
on AWS; Blob Storage, Key Vault and App Configuration on Azure; Cloud Storage on Google Cloud.

| Property | Default | Meaning |
|---|---|---|
| `http.max-connections` | 200 | pooled connections, which bound concurrent requests |
| `http.connect-timeout` | 10s | TCP and TLS connect timeout |
| `http.read-timeout` | 60s | maximum wait for response data |
| `http.idle-timeout` | 60s | idle connections are closed after this |
| `http.acquire-timeout` | 10s | maximum wait for a free connection (AWS) |

Durations take `500ms`, `30s` or `5m` style values, or ISO-8601 (`PT30S`). AWS uses the Apache 5 client
and Azure its default Netty client. Cloud Storage uses an Apache transport instead of the JDK's
`HttpURLConnection`, which keeps only 5 idle connections. Google's gRPC clients (Pub/Sub, Firestore,
Secret Manager, Parameter Manager) multiplex over HTTP/2 and aren't affected.

Upload part buffers come from a JVM-wide pool. Set the most it keeps idle with the system property
`halyx.buffer-pool.max-bytes` (256 MiB by default).

## AWS

```java
ProviderConfig.of("aws").region("eu-west-2")
```

Settings marked with a config type are set on that config (for example `TableConfig.builder(...).property(...)`)
rather than on the provider.

| Property | Set on | Meaning |
|---|---|---|
| `aws.retry-mode` | provider | SDK retry mode: `adaptive` (default), `standard` or `legacy` |
| `aws.s3.path-style` | provider | `true` for path-style addressing (emulators, S3-compatible stores) |
| `aws.s3.multipart-threshold` | provider | bytes above which a known-length `put` uses parallel multipart (64 MiB) |
| `aws.dynamodb.ttl-attribute` | `TableConfig` | the expiry attribute (`halyx_expires` by default) |
| `aws.target` | `TopicConfig` | `sqs` to publish straight to an SQS queue instead of SNS |
| `aws.ssm.secure` | `ParameterStoreConfig` | `true` to write `SecureString` parameters |
| `aws.elasticache.cache-name` | `CacheConfig` | the replication group id or serverless cache name, for IAM login |
| `aws.elasticache.serverless` | `CacheConfig` | `true` for ElastiCache Serverless |
| `aws.rds.region` | `SqlConfig` | the database's region, if it differs from the provider's |

## Azure

```java
ProviderConfig.of("azure")
        .property(AzureProperties.STORAGE_ACCOUNT, "acmefiles")
        .property(AzureProperties.SERVICE_BUS_NAMESPACE, "acme.servicebus.windows.net")
        .property(AzureProperties.COSMOS_ENDPOINT, "https://acme.documents.azure.com:443/")
        .property(AzureProperties.COSMOS_DATABASE, "orders")
```

| Property | Meaning |
|---|---|
| `azure.storage.account` | the storage account; the endpoint is `https://<account>.blob.core.windows.net` |
| `azure.cosmos.endpoint`, `azure.cosmos.database` | the Cosmos DB account endpoint and database |
| `azure.cosmos.key` | an account key; without it, Entra ID is used |
| `azure.servicebus.namespace` | `<name>.servicebus.windows.net`, for Entra ID login |
| `azure.servicebus.connection-string` | a shared-access-key connection string, instead of Entra ID |
| `azure.servicebus.entity` | on a `TopicConfig`: `queue` to send to a queue rather than a topic |
| `azure.servicebus.sessions` | on topics and subscriptions: `true` for session-enabled entities |
| `azure.servicebus.max-sessions` | on a subscription: sessions held at once (8) |
| `azure.servicebus.max-message-bytes` | the largest body: 262144 (standard tier) by default, up to 100 MB on premium |
| `azure.appconfig.connection-string` | on a `ParameterStoreConfig`: a connection string instead of Entra ID |
| `azure.appconfig.label` | on a `ParameterStoreConfig`: the App Configuration label to use |

Key Vault and App Configuration take their URL as the store's `location`; see
[Parameters and secrets](api/parameters.md).

## Google Cloud

```java
ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, "acme-prod")
```

| Property | Meaning |
|---|---|
| `gcp.project-id` | the project; defaults to the Application Default Credentials' project |
| `gcp.firestore.database` | the Firestore database id (`(default)` by default) |
| `gcp.firestore.items-collection` | on a `TableConfig`: the subcollection name under each partition |
| `gcp.parametermanager.render` | on a `ParameterStoreConfig`: `true` to resolve Secret Manager references |
| `gcp.sql.instance` | on a `SqlConfig`: connect through the Cloud SQL Java connector (`project:region:instance`) |

## HashiCorp Vault

```java
ProviderConfig.of("vault")
        .endpoint(URI.create("https://vault.internal:8200"))
        .property("vault.auth", "kubernetes")
        .property("vault.kubernetes.role", "orders-svc")
```

| Property | Meaning |
|---|---|
| `vault.auth` | `token` (default), `approle`, `kubernetes`, `token-file`, `gcp` or `azure` |
| `vault.auth-path` | the auth method's mount path (`approle` or `kubernetes` by default) |
| `vault.approle.role-id`, `vault.approle.secret-id` | AppRole login |
| `vault.kubernetes.role`, `vault.kubernetes.jwt-path` | Kubernetes login (the pod's service account token by default) |
| `vault.token-file` | a Vault Agent sink, read again whenever Vault rejects the token |
| `vault.gcp.role` | GCP login, with an identity token from the metadata server |
| `vault.azure.role`, `vault.azure.resource` | Azure login, with a managed identity token |
| `vault.namespace` | the Vault Enterprise or HCP namespace |
| `vault.ca-certificate` | a PEM file of CA certificates to trust |

The KV v2 mount is the parameter store's `location` (`secret` by default).

## In-memory

```java
ProviderConfig.of("memory")
```

No settings. Object storage, NoSQL, messaging, caches, and parameters and secrets live in the JVM. It's
ideal for unit tests.
