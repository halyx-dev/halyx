---
title: Getting started
nav_order: 2
---

# Getting started
{: .no_toc }

<details open markdown="block">
  <summary>Contents</summary>
  {: .text-delta }
1. TOC
{:toc}
</details>

## Requirements

- **Java 21** or later. Halyx uses virtual threads for parallel transfers and message listeners.
- The Halyx provider modules for the clouds you use. Each brings its cloud's SDK.
- For Spring Boot applications, Spring Boot **3.5** or **4**.

## Add the dependencies

Import the BOM so every Halyx module has the same version, then add the core API and the providers you
need:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.halyx</groupId>
      <artifactId>halyx-bom</artifactId>
      <version>1.0.2</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>dev.halyx</groupId>
    <artifactId>halyx-core</artifactId>
  </dependency>
  <dependency>
    <groupId>dev.halyx</groupId>
    <artifactId>halyx-aws</artifactId>      <!-- and/or halyx-azure, halyx-gcp, halyx-vault -->
  </dependency>
</dependencies>
```

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation(platform("dev.halyx:halyx-bom:1.0.2"))
    implementation("dev.halyx:halyx-core")
    implementation("dev.halyx:halyx-aws") // and/or halyx-azure, halyx-gcp, halyx-vault
}
```

Gradle (Groovy):

```groovy
dependencies {
    implementation platform('dev.halyx:halyx-bom:1.0.2')
    implementation 'dev.halyx:halyx-core'
    implementation 'dev.halyx:halyx-aws' // and/or halyx-azure, halyx-gcp, halyx-vault
}
```

| Module | What it adds |
|---|---|
| `halyx-core` | The portable API, error model, interceptors and an in-memory provider. No cloud SDKs. |
| `halyx-aws` | Amazon S3, DynamoDB, SNS, SQS, EventBridge, SSM Parameter Store, Secrets Manager, ElastiCache and RDS IAM login |
| `halyx-azure` | Blob Storage, Cosmos DB, Service Bus, Event Grid namespaces, Key Vault, App Configuration, and Entra ID login for caches and databases |
| `halyx-gcp` | Cloud Storage, Firestore, Pub/Sub, Eventarc Advanced, Secret Manager, Parameter Manager, and IAM login for Memorystore and Cloud SQL |
| `halyx-vault` | HashiCorp Vault KV v2 parameters and secrets |
| `halyx-reactor` | `Mono` and `Flux` views of every service, for Reactor, WebFlux and other reactive code (see [Reactive and async code](api/reactive.md)) |
| `halyx-micrometer` | Micrometer observations of every operation, and trace context carried through messages, events and stream records, without Spring (see [Errors, retries and interceptors](api/errors.md#observations-and-tracing)) |
| `halyx-spring-boot-starter` | Spring Boot auto-configuration (see [Spring Boot](spring-boot.md)) |

Providers are discovered with `ServiceLoader`: a module on the classpath is all it takes. SQL databases
also need your JDBC driver (`org.postgresql:postgresql`, `com.mysql:mysql-connector-j` or
`com.microsoft.sqlserver:mssql-jdbc`).

On the module path, the jars are automatic modules named after their root packages: `dev.halyx` for
`halyx-core`, `dev.halyx.aws`, `dev.halyx.azure`, `dev.halyx.gcp`, `dev.halyx.jdbc` for `halyx-sql`, and
`dev.halyx.spring` for the auto-configuration. Write `requires dev.halyx;` and `requires dev.halyx.aws;`.
Providers are found through their `META-INF/services` files, as they are on the classpath.

## Your first calls

A `Halyx` object holds one named connection per cloud account or region. It is thread-safe, meant to
live as long as the application, and closes every client it opened:

```java
try (Halyx halyx = Halyx.builder()
        .register("orders", ProviderConfig.of("aws").region("eu-west-2"))
        .build()) {

    CloudContext aws = halyx.provider("orders");

    BlobStore invoices = aws.blobStore("acme-invoices");
    invoices.put(BlobKey.of("invoices/42.pdf"), BlobContent.ofFile(Path.of("42.pdf")),
            PutOptions.builder().contentType("application/pdf").ifNotExists().build());

    try (Blob blob = invoices.get(BlobKey.of("invoices/42.pdf"))) {
        blob.content().transferTo(response.getOutputStream());
    } catch (NotFoundException e) {
        // the same exception on S3, Azure Blob Storage and Cloud Storage
    }
}
```

Credentials come from each cloud's default chain unless you pass them: environment variables, workload
identity (EKS, AKS, GKE), instance or managed identity, or your CLI login. A provider can also assume an AWS
IAM role, impersonate a Google service account, or log in to Azure as an Entra ID service principal. See
[Provider configuration](providers.md#credentials) for credentials, regions, endpoints and every setting.

## Use several clouds at once

Register one provider per cloud, or several per cloud, and use each by name:

```java
Halyx halyx = Halyx.builder()
        .register("aws", ProviderConfig.of("aws").region("eu-west-2"))
        .register("azure", ProviderConfig.of("azure").property(AzureProperties.STORAGE_ACCOUNT, "acmefiles"))
        .register("gcp", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, "acme-prod"))
        .build();

BlobStore reports = halyx.provider("azure").blobStore("reports");          // Azure Blob Storage
Cache<String> sessions = halyx.provider("aws").cache(sessionCacheConfig);   // ElastiCache
Publisher events = halyx.provider("gcp").publisher("order-events");        // Pub/Sub
```

## Test without a cloud

The `memory` provider implements object storage, NoSQL, messaging, caches, and parameters and secrets in
memory, with the same semantics the conformance kits check on the real services. It is ideal for unit
tests (SQL databases need a real database, for example in a Testcontainers container):

```java
Halyx halyx = Halyx.builder().register("test", ProviderConfig.of("memory")).build();
BlobStore store = halyx.provider("test").blobStore("any-bucket");
```

For integration tests, point a provider at an emulator with `ProviderConfig.endpoint(...)`, for example
LocalStack or [Floci](https://floci.io) for AWS.

## With Spring Boot

Add `halyx-spring-boot-starter` and a provider module, describe your resources in `application.yml`,
and inject them:

```yaml
halyx:
  providers:
    aws: { type: aws, region: eu-west-2 }
  blob-stores:
    invoices: { bucket: acme-invoices }
```

```java
@Service
class InvoiceService {
    InvoiceService(BlobStore invoices) { ... }
}
```

The [Spring Boot](spring-boot.md) page covers every resource type, listeners, health and metrics.

## Next steps

- [API guide](api/index.md): every service in depth.
- [Errors and retries](api/errors.md): handling failures the same way on every cloud.
- [Capabilities and native clients](api/capabilities-and-unwrap.md): provider-specific features.
- [Plain Java quickstart](https://github.com/halyx-dev/halyx/tree/main/halyx-samples/plain-java-quickstart): a complete runnable example without Spring.
