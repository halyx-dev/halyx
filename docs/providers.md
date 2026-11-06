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

A blank property value is no value, here and in every resource config's `property(...)`: it removes the setting.
So an optional setting can come from an environment variable that some deployments leave unset, as in
`gcp.sql.instance: ${ORDERS_SQL_INSTANCE:}`, and it never reaches a driver as an empty file path or instance name.

## Credentials

| `Credentials` | AWS | Azure | Google Cloud | HashiCorp Vault |
|---|---|---|---|---|
| `defaultChain()` (default) | the AWS default chain: environment, profile, SSO, web identity (EKS), container and instance roles | `DefaultAzureCredential`: environment, workload identity (AKS), managed identity, Azure CLI | Application Default Credentials: `GOOGLE_APPLICATION_CREDENTIALS`, workload identity (GKE), metadata server, `gcloud` login | the `VAULT_TOKEN` environment variable |
| `impersonate(target)` | assume the IAM role `target` (an ARN) through STS | not supported | impersonate the service account `target` (an email) | not supported |
| `servicePrincipal(...)` | not supported | an Entra ID application: client secret, certificate or federated token | not supported | not supported |
| `of(id, secret)` | access key ID and secret | storage account name and key (shared key, Blob Storage only) | not supported: use the default chain | `of("token", "<token>")` |
| `anonymous()` | unsigned requests, for emulators | no credentials, for emulators | no credentials, for emulators | |

Prefer the default chain. It covers workload identity on Kubernetes, managed identities and CLI logins,
with no secrets in configuration. Passwordless database, cache and Kafka logins
([SQL](api/sql.md), [caches](api/caches.md), [streams](api/streams.md)) need an identity: on Azure the default
chain or a service principal, on Google Cloud the default chain or impersonation.

### Assumed roles and impersonated service accounts

`Credentials.impersonate(target)` acts as another identity, with temporary credentials that are refreshed
before they expire. It starts from the default chain:

```java
// AWS: an IAM role in another account, through STS
Credentials orders = Credentials.impersonate("arn:aws:iam::123456789012:role/orders")
        .externalId("acme")                  // when the role's trust policy requires one
        .sessionName("billing")              // shown in CloudTrail; "halyx" otherwise
        .lifetime(Duration.ofMinutes(30));   // the role's default otherwise

// Google Cloud: a service account, through the IAM Credentials API
Credentials reports = Credentials.impersonate("reports@acme-prod.iam.gserviceaccount.com");
```

On AWS, `.from(Credentials.of(accessKeyId, secret))` starts from an access key instead. The identity the
provider starts from needs permission to act as the target: `sts:AssumeRole` in the role's trust policy on AWS,
or `roles/iam.serviceAccountTokenCreator` on the service account on Google Cloud. A session name and an
external ID are AWS settings, and Google Cloud refuses them.

Register one provider per account or role. Each gets its own credentials, so one application can use buckets in
several AWS accounts, each through its own role.

### Entra ID service principals

Code running outside Azure, on AWS, on Google Cloud or on premises, has no managed identity, so it logs in to
Azure as a Microsoft Entra ID application (a service principal):

```java
Credentials.servicePrincipal(tenantId, clientId, clientSecret);
Credentials.servicePrincipalWithCertificate(tenantId, clientId, Path.of("/etc/halyx/app.pem"));   // certificate and private key
Credentials.servicePrincipalWithFederatedToken(tenantId, clientId, Path.of("/var/run/secrets/azure/token"));
```

A federated token needs no stored secret. Entra ID trusts the token's issuer through a federated identity
credential on the application, so a pod on EKS or GKE can log in to Azure with the Kubernetes service account
token its cluster projects into it. Every Azure service uses the service principal, including Entra ID logins to
databases, caches and Event Hubs.

## HTTP connections

Each provider connection shares one pooled HTTP client across its services: S3, SSM, Secrets Manager and
Lambda on AWS; Blob Storage, Key Vault, App Configuration and Azure Functions on Azure; Cloud Storage and Cloud
Run functions on Google Cloud.

| Property | Default | Meaning |
|---|---|---|
| `http.max-connections` | 200 | pooled connections, which bound concurrent requests |
| `http.connect-timeout` | 10s | TCP and TLS connect timeout |
| `http.read-timeout` | 60s | maximum wait for response data; a function's own timeout applies to its invocations instead (on AWS, a longer one gets a Lambda connection pool with that read timeout) |
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
| `aws.msk.region` | `StreamConfig` | the MSK cluster's region, for IAM tokens, if it differs from the provider's |
| `aws.lambda.qualifier` | `FunctionConfig` | the Lambda version or alias to invoke; the unpublished version if unset |

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
| `azure.cosmos.key` | an account key; without it, Entra ID is used. Given as a supplier, it's asked for every `azure.key-refresh-interval` |
| `azure.key-refresh-interval` | how often keys given as suppliers (`azure.cosmos.key`, `azure.eventgrid.access-key`) are asked for again, ISO-8601 (`PT1M`) |
| `azure.servicebus.namespace` | `<name>.servicebus.windows.net`, for Entra ID login |
| `azure.servicebus.connection-string` | a shared-access-key connection string, instead of Entra ID; read once, when the client is built |
| `azure.servicebus.entity` | on a `TopicConfig`: `queue` to send to a queue rather than a topic |
| `azure.servicebus.sessions` | on topics and subscriptions: `true` for session-enabled entities |
| `azure.servicebus.max-sessions` | on a subscription: sessions held at once (8) |
| `azure.servicebus.max-message-bytes` | the largest body: 262144 (standard tier) by default, up to 100 MB on premium |
| `azure.event-hubs.scope` | on a `StreamConfig`: the Entra ID scope, if not the namespace of the first bootstrap server |
| `azure.appconfig.connection-string` | on a `ParameterStoreConfig`: a connection string instead of Entra ID |
| `azure.appconfig.label` | on a `ParameterStoreConfig`: the App Configuration label to use |
| `azure.functions.endpoint` | on a `FunctionConfig`: the function app's URL, such as `https://orders.azurewebsites.net` |
| `azure.functions.audience` | on a `FunctionConfig`: the Entra ID app that App Service Authentication checks; tokens are for `<audience>/.default` |
| `azure.functions.key` | on a `FunctionConfig`: a function key, sent as `x-functions-key`, instead of an Entra ID token; given as a supplier, it's asked for on every call |
| `azure.functions.route` | on a `FunctionConfig`: the function's whole path on the app, if it isn't `api/<name>`, such as `api/products/enrich` |

Key Vault and App Configuration take their URL as the store's `location`; see
[Parameters and secrets](api/parameters.md).

Keys that rotate can be given as suppliers, such as a read from a parameter store, and Halyx follows the rotation
without a restart:

| Key | Asked for |
|---|---|
| `azure.functions.key` | on every call |
| `azure.cosmos.key`, `azure.eventgrid.access-key` | when the client is built, then every `azure.key-refresh-interval`; Cosmos DB and Event Grid use the new key from the next request |
| `azure.servicebus.connection-string`, `azure.appconfig.connection-string` | once, when the client is built: the SDKs keep a copy, so for keys that rotate, log in with Entra ID |

```java
ParameterStore secrets = ...;   // with a cacheTtl, so the refreshes are cheap
ProviderConfig.of("azure")
        .property(AzureProperties.COSMOS_ENDPOINT, "https://acme.documents.azure.com:443/")
        .property(AzureProperties.COSMOS_KEY, () -> secrets.require("orders-cosmos-key"))
```

A failed refresh keeps the current key and is tried again at the next interval. Each of these services keeps two
keys, so a rotation that switches to the other key, updates the secret, and regenerates the old key after the
refresh interval plus the store's cache TTL fails no requests.

### Keys on Key Vault

A key store is one Key Vault, its URL the store's `location`, such as `https://acme.vault.azure.net`, and a key's
name is the Key Vault key's. With `nativeNames(true)`, the versionless key URL in that vault is accepted too. Halyx
never creates, rotates or deletes keys: create them yourself, RSA 2048-bit or larger with `wrapKey` and `unwrapKey`
for encryption, and RSA, EC P-256 or EC P-384 with `sign` for signing. An RSA key allowing both can do either: give
its handle a `purpose`. Give the application the **Key Vault Crypto User** role on the vault.

Data keys are wrapped on the vault with `RSA-OAEP-256`, and sealed data names the key and the version that wrapped it,
so data keeps opening after a rotation while that version is enabled. Key Vault enables and disables versions one by
one: a key reports itself disabled when its current version is, and an older disabled version refuses only the data
it wrapped. Every wrap, unwrap and signature runs on the vault, never locally, and has a non-blocking path. MACs and
server-side rewrap need Managed HSM, which Halyx doesn't support yet.

Every wrap and unwrap is an RSA operation on the vault, and Key Vault limits them per vault and region: 4,000 per 10
seconds for software RSA 2048-bit keys, 2,000 for HSM ones, and fewer for larger keys (1,000 and 500 software for
3,072 and 4,096 bits), with the subscription's total at five times one vault's. A busy service reaches this sooner
than on AWS or Google Cloud, so on Azure:

- turn on `dataKeyReuse` where the service writes a lot, and `dataKeyCache` where it reads a lot (see
  [Performance and caches](api/keys.md#performance-and-caches))
- give each service, or at least each busy one, its own vault
- use 2048-bit keys unless policy needs larger ones

Throttled calls fail with `ThrottledException`, which is retryable, so the SDK's retries and a `RetryInterceptor`
apply. Microsoft's [Key Vault service limits](https://learn.microsoft.com/en-us/azure/key-vault/general/service-limits)
has the current figures.

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
| `gcp.kafka.principal` | on a `StreamConfig`: the principal's email for Managed Kafka IAM login, when the credentials don't name one |
| `gcp.functions.region` | on a `FunctionConfig`: the function's region, for its default URL `https://<region>-<project>.cloudfunctions.net/<name>` |
| `gcp.functions.url` | on a `FunctionConfig`: the function's URL, such as a `run.app` URL, instead of the default |
| `gcp.functions.audience` | on a `FunctionConfig`: the ID tokens' audience, if it isn't the function's URL |

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

### Keys on Transit

A key store is the Transit mount at its `location` (`transit` by default), and a key's name is the Transit key's. Halyx
never creates, rotates or deletes keys: create them yourself, `aes256-gcm96` (or another symmetric type) for
encryption, `rsa-2048`, `rsa-3072`, `rsa-4096`, `ecdsa-p256` or `ecdsa-p384` for signing, and `hmac` for MACs.

Give the application this policy, for the `transit` mount:

```hcl
path "transit/encrypt/*" { capabilities = ["update"] }
path "transit/decrypt/*" { capabilities = ["update"] }
path "transit/rewrap/*"  { capabilities = ["update"] }
path "transit/sign/*"    { capabilities = ["update"] }
path "transit/hmac/*"    { capabilities = ["update"] }
path "transit/verify/*"  { capabilities = ["update"] }
path "transit/keys/*"    { capabilities = ["read"] }
path "transit/keys"      { capabilities = ["list"] }
```

It grants `update` and never `create` on `transit/encrypt/*`: with `create`, Transit makes a missing key on its first
`encrypt` instead of failing.

Transit has no disabled state, so a key's metadata always reports it enabled. To withdraw a key, deny its paths in
another policy on the application's token. Name each path exactly: Vault applies the matching path of highest
priority, and `transit/encrypt/*` outranks a pattern such as `transit/+/orders`. Calls then fail with
`AccessDeniedException`. Wrapping, unwrapping, signing, MACs and metadata and public key reads have a non-blocking
path on Transit.

## In-memory

```java
ProviderConfig.of("memory")
```

No settings. Object storage, NoSQL, messaging, events, streams, functions, caches, and parameters and secrets
live in the JVM; functions are handlers registered with `InMemoryFunctions`. It's
ideal for unit tests.
