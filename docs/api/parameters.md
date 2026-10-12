---
title: Parameters and secrets
parent: API guide
nav_order: 7
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
| `cacheTtl(duration)` | cache reads for this long; `refresh()` clears the cache |
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

## Watching for changes

`watch` polls a prefix and reports added, updated and removed values, which suits credential rotation:

```java
try (AutoCloseable watch = secrets.watch("db", Duration.ofMinutes(1), changes ->
        changes.updated().forEach((name, p) -> pool.rotateCredentials(p.fields())))) {
    ...
}
```

One daemon thread serves all watchers. A detected change also refreshes the store's cache with the values
the watch saw. Failed polls are logged and retried. `statistics()` counts cache hits, backend reads,
detected changes and watch failures.

In Spring Boot, `spring.config.import=halyx:<store>` loads a store into the `Environment` and can reload
it. See [Spring Boot](../spring-boot.md#configuration-import).

## Native names

`ParameterStoreConfig.nativeNames(true)` uses the backend's own names as they are, with no validation and
no separator translation; the prefix is then a literal string prefix. Use it for existing values like
`/app/db_password`, or AWS-managed `rds!db-...` secrets.

## Backend behaviour

{: .note }
Some backends are **eventually consistent** or delete **asynchronously**. Halyx never waits on your
behalf: it returns or throws at once, and conflicts are the retryable `UnavailableException`.

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
