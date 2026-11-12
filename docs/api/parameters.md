---
title: Parameters and secrets
parent: API guide
nav_order: 9
---

# Parameters and secrets: `ParameterStore`
{: .no_toc }

One API for configuration values and secrets on every cloud and on HashiCorp Vault.

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

| `ParameterKind` | AWS | Azure | Google Cloud | Also |
|---|---|---|---|---|
| `PARAMETERS` | SSM Parameter Store (`SecureString` with `aws.ssm.secure=true`) | App Configuration (optional label) | Parameter Manager (location `global` or a region) | HashiCorp Vault KV v2, in-memory |
| `SECRETS` | Secrets Manager | Key Vault (location = the vault URL) | Secret Manager | HashiCorp Vault KV v2, in-memory |

## Reading and writing

```java
ParameterStore secrets = halyx.provider("aws").parameterStore(
        ParameterStoreConfig.secrets().prefix("orders-svc").cacheTtl(Duration.ofMinutes(5)).build());

String password = secrets.require("db/password");          // Secrets Manager secret "orders-svc/db/password"
Optional<Parameter> key = secrets.get("api/key");          // empty if missing
Map<String, Parameter> db = secrets.getAll("db");           // everything at or below orders-svc/db
secrets.put("api/key", newKey);                            // creates, or adds a new version
secrets.delete("api/key");                                  // idempotent

ParameterStore config = halyx.provider("aws").parameterStore(ParameterStoreConfig.parameters().build());
Optional<Integer> poolSize = config.get("db/pool/size", Integer::valueOf);
```

`require` throws `NotFoundException` for a missing name; `get` returns an empty `Optional`. A `Parameter`
carries the `name`, `value`, `version` and `lastModified` time.

**Store options:**

| `ParameterStoreConfig` | Meaning |
|---|---|
| `prefix(...)` | a namespace for every name the store reads and writes |
| `cacheTtl(duration)` | cache reads for this long; `refresh()` clears the cache. `ping()` always reads from the backend, for health checks |
| `location(...)` | the Key Vault URL, the App Configuration endpoint, the Parameter Manager location, or the Vault KV mount (`secret` by default) |
| `nativeNames(true)` | use the backend's own names as they are (see [native names](#native-names)) |
| `property(name, value)` | backend settings such as `aws.ssm.secure`, `azure.appconfig.label` or `gcp.parametermanager.render` |

## Portable names

Names are segments of letters, digits and single dashes separated by `/`, such as `orders/db/password`.
Each backend stores them in its own naming scheme:

| Backend | `orders/db/password` is stored as |
|---|---|
| SSM Parameter Store | `/orders/db/password` |
| Key Vault | `orders--db--password` |
| Secret Manager, Parameter Manager | `orders__db__password` |
| Secrets Manager, App Configuration, Vault | `orders/db/password` |

`getAll` returns everything at or below a prefix, and never matches partial segments: `db` doesn't match
`dbx/...`.

## JSON secrets

Read fields of JSON object values, such as RDS and Aurora managed secrets or multi-field Vault secrets:

```java
String rdsPassword = secrets.require("db/rds", "password");              // one field
Parameter credentials = secrets.get("db/rds").orElseThrow();
Optional<String> user = credentials.field("username");
Map<String, String> all = credentials.fields();                          // flattened: db.host, hosts[0]
```

## Versions

Where the backend keeps versions, earlier ones are readable:

```java
Parameter current = secrets.get("api/key").orElseThrow();
Optional<Parameter> previous = secrets.get("api/key", previousVersionId);
```

`capabilities().supports(ParameterCapability.VERSIONS)` says whether a store keeps versions. App
Configuration doesn't.

## Asynchronous calls

`async()` gives the store's asynchronous view: `get`, `require`, `getAll`, `put`, `delete` and `ping` as
`CompletableFuture`s, through the same read cache, statistics and interceptors as the blocking calls. A cached
read completes at once; the rest go to the backend with no thread waiting, on every backend: SSM Parameter Store,
Secrets Manager, App Configuration, Key Vault, Secret Manager, Parameter Manager and Vault. Three calls still
block, on a virtual thread: Key Vault deletes and the recovery a conflicting Key Vault write starts (the SDK
starts its long-running operations only through a poller), and Vault's login, when the token is missing or
about to expire.

```java
secrets.async().require("api/key").thenAccept(client::authenticate);
```

## Watching for changes

`watch` polls a prefix and reports added, updated and removed values, which suits credential rotation:

```java
try (AutoCloseable watch = secrets.watch("db", Duration.ofMinutes(1), changes ->
        changes.updated().forEach((name, p) -> pool.rotateCredentials(p.fields())))) {
    ...
}
```

One daemon thread serves all watchers. Each change a listing shows is confirmed with a read by name before
it's reported, since some listings briefly lag or go back and forth after a write: a change is reported once,
when the read agrees, and never undone by a stale listing. A detected change also refreshes the store's cache
with the values the watch saw. Failed polls are logged and retried. `statistics()` counts cache hits, backend reads,
detected changes and watch failures.

In Spring Boot, `spring.config.import=halyx:<store>` loads a store into the `Environment` and can reload
it. See [Spring Boot](../spring-boot.md#configuration-import).

## Feature flags

A parameter store makes a portable home for feature flags: the same flags, managed the same way, on SSM Parameter
Store, App Configuration, Parameter Manager or Vault. Halyx stores them; a flag library evaluates them. Any library with
a pluggable source works, such as flipjack's `PollingFeatureToggles`, a Togglz `StateRepository`, an FF4J
`FeatureStore` or an OpenFeature provider. This example uses [flipjack](https://github.com/kamranzafar/flipjack), which
routes calls between an implementation and its fallback so business code has no flag checks:

```java
@Bean
OverridableFeatureToggles featureToggles(@Qualifier("settings") ParameterStore settings) {
    return PollingFeatureToggles.builder(() -> {
                Map<String, String> flags = new LinkedHashMap<>();
                settings.getAll("features").forEach((name, p) ->           // features/bulk-discount -> bulk-discount
                        flags.put(name.substring(name.lastIndexOf('/') + 1), p.value()));
                return flags;
            })
            .interval(Duration.ofSeconds(30))
            .requireInitialLoad()
            .build()
            .withOverrides();
}

@Component
@Toggleable(value = "bulk-discount", fallback = StandardPricing.class)
class BulkDiscountPricing implements OrderPricing { ... }
```

- `getAll` returns names relative to the store's prefix, the same on every backend, so `features/bulk-discount` under
  the prefix `orders-svc` is the flag `bulk-discount`.
- Flipjack polls, so a change takes effect within the poll interval plus the store's `cacheTtl`. Give a flags store
  no cache, or a short one.
- A flag that is missing from the store is off. Features start off until the store turns them on, and deleting a flag
  turns it off at the next poll.
- `requireInitialLoad()` stops the start when the store can't be read, rather than starting with every flag off.
- `withOverrides()` adds kill switches: `toggles.override("bulk-discount", false)` turns a feature off at once, even
  while the store is unreachable, and `clearOverride` hands it back to the store.

A parameter store holds on/off flags per deployment. Per-user targeting and percentage rollouts need a flag platform
such as flagd, Unleash or LaunchDarkly; flipjack's `flipjack-openfeature` module takes any OpenFeature provider as its
source in place of `PollingFeatureToggles`, and the routing stays the same.

Without Spring, build the same `PollingFeatureToggles` and wrap beans with `ToggledFeature.of(...)`. The
[portable orders sample](../portable-service.md#feature-flags) runs this on every cloud.

## Keys for other services

A store can hold the keys and passwords that other services log in with, read as they're used so a rotation takes
effect without a restart: a SQL password (`SqlAuth.password(user, supplier)`), a cache password
(`CacheAuth.password(supplier)`), a Kafka password (`StreamAuth.password(mechanism, user, supplier)`), an Azure function
key, a Cosmos DB account key or an Event Grid access key (see [Provider configuration](../providers.md#azure)). Give the store a
`cacheTtl`, which also bounds how long a rotation takes to land. In Spring Boot, these are `auth.secret` and
`secrets` entries; see [Spring Boot](../spring-boot.md#secrets-in-settings).

### A store on another cloud

A parameter store names its own provider, so a service on one cloud can read its keys from a store on another, or
from Vault: a function on Azure whose key is in AWS Secrets Manager, for example.

```java
ParameterStore shared = halyx.provider("aws").parameterStore(
        ParameterStoreConfig.secrets().cacheTtl(Duration.ofMinutes(5)).build());

FunctionInvoker quote = halyx.provider("azure").functionInvoker(FunctionConfig.builder("rate-quote")
        .property(AzureProperties.FUNCTIONS_ENDPOINT, "https://pricing.azurewebsites.net")
        .property(AzureProperties.FUNCTIONS_KEY, () -> shared.require("rate-quote-key"))
        .build());
```

What to plan for:
- **Login to the store's cloud.** The service needs an identity there as well as on its own cloud. Workload identity
  federation avoids a stored key: the store's provider uses the default chain, which logs in with a token the
  cluster issues (on AWS, a role trusting the cluster's issuer, through `AWS_ROLE_ARN` and
  `AWS_WEB_IDENTITY_TOKEN_FILE`; on Google Cloud, a workload identity pool's credential configuration). A static key
  for the store's cloud would itself need rotating.
- **Availability.** The service depends on a second cloud. Keys are read when clients are built, so a start fails
  while the store is unreachable; the read cache rides out shorter outages once running.
- **Latency and egress.** A read after the cache expires crosses clouds. A `cacheTtl` of minutes makes reads rare.
- **Bootstrap credentials** stay each cloud's workload identity, since they can't come from the store.

## Server-side encryption with a key

The services encrypt values at rest with a key they manage. `ParameterStoreConfig.Builder.serverSideKey(key)` has
them use a key you choose from the same cloud's key service instead (`ParameterCapability.SERVER_SIDE_KMS`). Reads
need nothing: the service decrypts with whatever key a value was stored with.

```java
CryptoKey key = halyx.provider("aws").keyStore().key("orders-secrets");
ParameterStore secrets = halyx.provider("aws").parameterStore(
        ParameterStoreConfig.secrets().prefix("orders").serverSideKey(key).build());
secrets.put("db/password", password);   // a new secret, encrypted with orders-secrets
```

| Store | What the key is used for |
|---|---|
| SSM Parameter Store | `KeyId` on every put, so each new version uses it. Needs `aws.ssm.secure=true` (`SecureString`); a store without it is refused when created |
| Secrets Manager | `KmsKeyId` when a secret is created |
| Secret Manager | A customer-managed key in the automatic replication of new secrets, which must be in a `global` key ring |
| Parameter Manager | `kms_key` on new parameters, which must be in a key ring in the store's location |
| Azure Key Vault, App Configuration, Vault | Not supported: Key Vault and App Configuration encrypt with the vault's or store's own key, and Vault with its barrier |

- **The key applies when a parameter is created.** One that already exists keeps the key it was created with, on
  every store but SSM: a put adds a version, and the services fix the key per secret. To move an existing secret to
  a new key, use the service's own tools (Secrets Manager `UpdateSecret`, or recreate it). SSM encrypts each new
  version with the key the put names, so a put through a store without the key goes back to the account's default.
- **Checks.** Creating the store checks the capability and that the key belongs to the store's provider, with no
  key service call. The first put reads the key's metadata (cached, as for any key) and refuses a key that can't
  encrypt. A key in the wrong location for Secret Manager or Parameter Manager is refused before anything is
  written.
- **Configurations with a key are separate stores.** `ParameterStoreConfig` compares the key by identity, so two
  configurations with the same key handle share one store, and a configuration without it is another store.
- **Permissions.** The service's caller needs to use the key: on AWS, `kms:Decrypt` on it for reads, and for writes
  `kms:Encrypt` (SSM standard parameters) or `kms:GenerateDataKey` (Secrets Manager, SSM advanced parameters); on
  Google Cloud, the Secret Manager or Parameter Manager service agent needs
  `roles/cloudkms.cryptoKeyEncrypterDecrypter` on it.

In Spring Boot, set `server-side-key` on the store: see [Spring Boot](../spring-boot.md#parameters-and-secrets).

## Native names

`ParameterStoreConfig.nativeNames(true)` uses the backend's own names as they are, with no validation and
no separator translation; the prefix is then a literal string prefix. Use it for existing values like
`/app/db_password`, or AWS-managed `rds!db-...` secrets.

## Backend behaviour

{: .note }
Some backends are **eventually consistent** or delete **asynchronously**. Halyx never waits on your
behalf: it returns or throws at once, and conflicts are the retryable `UnavailableException`.

- **AWS Systems Manager Parameter Store:** `getAll` (`GetParametersByPath`) can return an older view for a
  second or two after a write, including one older than a listing just before it; reads by name are current.
  `watch` confirms changes by name, so it isn't affected.
- **AWS Secrets Manager:** `getAll` (and so `watch` and config reloads) is eventually consistent, so a
  secret written moments ago may not be listed yet. A read by name can briefly lag a write. Deletes
  finish asynchronously (up to about 20 seconds measured): until then a deleted secret may still be read,
  and writing its name throws the retryable `UnavailableException`. Deletes skip the recovery window. The
  IAM policy needs `secretsmanager:ListSecrets` for `getAll`, plus `GetSecretValue`, `PutSecretValue`,
  `CreateSecret` and `DeleteSecret`. Granting `BatchGetSecretValue` on `"*"` lets `getAll` read 20 secrets
  per call; without it Halyx reads them one at a time and logs a warning.
- **Azure Key Vault:** deletes are soft deletes that finish asynchronously, and `delete` doesn't wait.
  Writing a name that is soft-deleted, or still being deleted or recovered, throws the retryable
  `UnavailableException`. If the name was soft-deleted, the write also starts its recovery, so a retry
  stores the new value without needing the purge permission.
- **Google Parameter Manager:** there's no "latest" alias and the caller names each version. Halyx writes
  version ids that sort by creation time and reads the newest enabled version, which is also correct for
  versions written by other tools. A read lists versions and then fetches one, so give these stores a
  `cacheTtl`. Deletes remove all versions, then the parameter.
- **HashiCorp Vault:** deletes remove all versions. Secrets written by other tools without a `value` field
  are read as their JSON data.

## HashiCorp Vault

The `halyx-vault` module supports:
- token auth, or `VAULT_TOKEN` from the environment
- a token file (a Vault Agent sink), read again whenever Vault rejects the token
- AppRole, Kubernetes, GCP and Azure login
- Vault namespaces
- a private CA (`vault.ca-certificate`)

It renews tokens before they expire. It uses the JDK HTTP client and a built-in JSON codec, so it adds no
dependencies that could clash with the cloud SDKs. See
[Provider configuration](../providers.md#hashicorp-vault).

## Native clients

| Backend | `unwrap(...)` |
|---|---|
| SSM Parameter Store | `SsmClient` |
| Secrets Manager | `SecretsManagerClient` |
| App Configuration | `ConfigurationClient` |
| Key Vault | `SecretClient` |
| Secret Manager | `SecretManagerServiceClient` |
| Parameter Manager | `ParameterManagerClient` |
| HashiCorp Vault | `VaultClient` (Halyx's own client) |
