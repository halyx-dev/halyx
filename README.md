<div align="center">
<pre>
     .--.           .--.           .--.     
  .-(    ).      .-(    ).      .-(    ).   
 (___.__)__)    (___.__)__)    (___.__)__)  
  ██╗  ██╗ █████╗ ██╗     ██╗   ██╗██╗  ██╗
  ██║  ██║██╔══██╗██║     ╚██╗ ██╔╝╚██╗██╔╝
  ███████║███████║██║      ╚████╔╝  ╚███╔╝ 
  ██╔══██║██╔══██║██║       ╚██╔╝   ██╔██╗ 
  ██║  ██║██║  ██║███████╗   ██║   ██╔╝ ██╗
  ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝   ╚═╝   ╚═╝  ╚═╝
                                            
        the multi-cloud Java library        
     one Java API for AWS · Azure · GCP     
</pre>
</div>

<p align="center">
  <a href="https://halyx-dev.github.io/halyx/"><img alt="Documentation" src="https://img.shields.io/badge/docs-halyx-dev.github.io%2Fhalyx-0a7bbb"></a>
  <a href="LICENSE"><img alt="License: Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
  <img alt="Java 21+" src="https://img.shields.io/badge/java-21%2B-orange">
  <img alt="Spring Boot 3.5 and 4" src="https://img.shields.io/badge/spring%20boot-3.5%20%7C%204-6db33f">
  <img alt="AWS, Azure, Google Cloud" src="https://img.shields.io/badge/clouds-AWS%20%7C%20Azure%20%7C%20Google%20Cloud-informational">
</p>

**Halyx** is a Java API that gives microservices one consistent way to use Cloud Native services like Storage, SQL, NoSQL,
Caches, Messaging, Streams (managed Kafka), and Parameters and Secrets across **AWS**, **Azure** and **GCP**.

With Halyx you can build **portable multi-cloud microservices and applications**. A single microservice or application can work with
services on several clouds at once, such as files on S3, a cache on Azure, events on Pub/Sub and secrets
in Vault. Each cloud is registered as a named provider and used through the same API.

| | |
|---|---|
| **8 services, 3 clouds, 1 API** | storage, NoSQL, messaging, events, Kafka streams, SQL, caches, parameters and secrets on AWS, Azure and Google Cloud |
| **Same behaviour, proven** | one conformance kit per service, run against every provider on emulators and [on real cloud accounts, with published results](https://halyx-dev.github.io/halyx/cloud-testing/) |
| **No passwords** | databases, caches and Kafka log in with AWS IAM, Microsoft Entra ID or Google IAM |
| **A library, not a platform** | runs inside your application: no sidecar, no agent, and the native SDK client is one `unwrap` away |
| **Spring Boot 3.5 and 4** | a starter with listeners, Spring Data repositories, R2DBC, `@Cacheable`, health, metrics and `spring.config.import` |

**[Documentation](https://halyx-dev.github.io/halyx/)** · [Getting started](https://halyx-dev.github.io/halyx/getting-started.html) · [API guide](https://halyx-dev.github.io/halyx/api/) ·
[Spring Boot](https://halyx-dev.github.io/halyx/spring-boot.html) · [Cloud testing](https://halyx-dev.github.io/halyx/cloud-testing/) · [Roadmap](https://halyx-dev.github.io/halyx/roadmap.html)

## Why Halyx

Most "cloud-agnostic" microservices aren't portable. The business logic may be portable, but the code around it is
written against one cloud's SDK: `S3Client` here, `ServiceBusSenderClient` there, each with its own types,
error codes, retry rules and consistency quirks. Moving a service to another cloud, or running it on two,
means rewriting and retesting all of that.

Halyx was created to make that code portable too, without giving up what each cloud does well:

- **One API per service, the same on every cloud.** A `BlobStore` is a `BlobStore` on S3, Azure Blob
  Storage and Cloud Storage.
- **Move between clouds by configuration.** Point a blob store, cache or topic at another provider and
  the code stays the same, which keeps migrations, failover and exit plans realistic.
- **The same behaviour, not just the same signatures.** Conditional writes, versions, ordering, expiry,
  retries and errors such as `NotFoundException` mean the same everywhere. Shared conformance kits check
  every provider against emulators on every build and
  [against the real clouds](https://halyx-dev.github.io/halyx/cloud-testing/).
- **Several clouds in one service.** Each cloud is a named provider, so one microservice can store files
  on S3, publish to Pub/Sub and cache in Azure, with one error model and one set of tests.
- **No lowest common denominator.** Differences are declared as capabilities, and `unwrap` hands you the
  native SDK client for anything provider-specific.
- **Passwordless and production-ready.** Databases, caches and streams log in with AWS IAM, Microsoft Entra ID or
  Google IAM. Parallel transfers, batching, checksums, presigned URLs, message listeners, fail-open
  caches and Micrometer observability are built in.

## Who it's for

- **Products installed into each customer's own cloud.** Ship one build to customers on AWS, Azure or Google
  Cloud instead of maintaining three versions of your storage, messaging and secrets code.
- **Regulated teams that need a credible exit plan.** Regulations such as the EU's DORA, the UK PRA's
  SS2/21 and APRA's CPS 230 in Australia expect a realistic plan for leaving or replacing a cloud provider. With Halyx the plan is a configuration change, backed by the same tests
  passing on every cloud.
- **Data residency.** Run the same service on AWS in one country and on Azure or Google Cloud in another.
- **Services that use several clouds at once**, such as a bucket on one cloud and a queue on another.
- **Any team that wants fast tests.** The in-memory provider and emulators behave like the real services, so
  most tests don't need a cloud account, even if you only use one cloud.

## How it compares

| | Halyx | Spring Cloud AWS, Azure, GCP | Dapr | MultiCloudJ |
|---|---|---|---|---|
| Clouds | AWS, Azure, Google Cloud | One per project | Many, through components | AWS, Google Cloud, Alibaba Cloud |
| Languages | Java | Java | Any | Java |
| One API on AWS, Azure and Google Cloud | Yes | No: one project per cloud | Yes | No Azure |
| Runs inside your application | Yes | Yes | No: a sidecar | Yes |
| Same behaviour checked on every cloud | Yes, on real clouds | No | Varies by component | Not documented |
| SQL, caches, Kafka and secrets with IAM logins | Yes | Per cloud | Partly | No |
| Native SDK client available | Yes | Yes | No | Not documented |
| Spring Boot starter | Yes | Yes | Yes | No |

Each of these is the right choice for some teams. Dapr suits services in many languages and has workflows and
actors; Spring Cloud suits teams staying on one cloud; MultiCloudJ covers Alibaba Cloud.

## How it makes microservices cloud-agnostic

```
                              your microservice
                                     │
  BlobStore · DocumentStore · Publisher · Subscription · Database · Cache · ParameterStore
                                     │    one API · one error model · one set of tests
             ┌───────────────────────┼───────────────────────┐
            AWS                    Azure                Google Cloud       (+ HashiCorp Vault, in-memory)
```

Your code depends on the Halyx API. Which cloud serves each resource is **configuration**: the provider
type, region and resource names. So the same build can:

- run on AWS in one region and on Azure in another, for data residency or customer requirements
- move a single resource, such as one bucket, to another cloud without touching code
- fail over, or exit a cloud, with a plan that is realistic rather than theoretical
- run unit tests against the in-memory provider and integration tests against emulators

[`halyx-samples/portable-orders`](halyx-samples/portable-orders) shows it end to end: a Spring Boot service using every
service below, built once as one image, which runs on AWS, Azure or Google Cloud by Spring profile alone. See
[Portable microservice](https://halyx-dev.github.io/halyx/portable-service.html).

| Service | Halyx API | AWS | Azure | Google Cloud | Also |
|---|---|---|---|---|---|
| Object storage | `BlobStore` | Amazon S3 | Azure Blob Storage | Cloud Storage | in-memory |
| NoSQL documents | `DocumentStore` | DynamoDB | Cosmos DB for NoSQL | Firestore | in-memory |
| Messaging | `Publisher`, `Subscription` | SNS, SQS | Service Bus | Pub/Sub | in-memory |
| Events | `CloudEvent`, `EventPublisher` | EventBridge | Event Grid namespaces | Eventarc Advanced | in-memory |
| Streams | `StreamProducer`, `StreamConsumer` | MSK | Event Hubs (Kafka endpoint) | Managed Service for Apache Kafka | any Kafka cluster, in-memory |
| SQL databases | `Database` (JDBC `DataSource`) | RDS, Aurora | Azure Database for PostgreSQL and MySQL, Azure SQL | Cloud SQL | any PostgreSQL, MySQL, SQL Server |
| Caches | `Cache` | ElastiCache | Azure Cache for Redis, Azure Managed Redis | Memorystore | any Redis or Valkey, in-memory |
| Parameters and secrets | `ParameterStore` | SSM Parameter Store, Secrets Manager | App Configuration, Key Vault | Parameter Manager, Secret Manager | HashiCorp Vault, in-memory |

## Using it with Spring Boot

Add the starter and the provider modules you need:

```xml
<dependency>
  <groupId>dev.halyx</groupId>
  <artifactId>halyx-spring-boot-starter</artifactId>
</dependency>
<dependency>
  <groupId>dev.halyx</groupId>
  <artifactId>halyx-aws</artifactId>
</dependency>
```

Describe your resources in `application.yml`:

```yaml
halyx:
  providers:
    cloud: { type: aws, region: eu-west-2 }       # or azure, gcp
  blob-stores:
    invoices: { bucket: acme-invoices }
  topics:
    order-events: { name: orders }
  subscriptions:
    billing: { name: orders-billing }
  databases:
    orders:
      engine: postgresql
      host: orders.cluster-abc.eu-west-2.rds.amazonaws.com
      database: orders
      auth: { type: iam, username: orders_app }   # no password: an IAM token
```

Inject them and use them:

```java
@Service
class InvoiceService {
    private final BlobStore invoices;
    private final Publisher events;

    InvoiceService(@Qualifier("invoices") BlobStore invoices, @Qualifier("order-events") Publisher events) {
        this.invoices = invoices;
        this.events = events;
    }

    void store(Invoice invoice) {
        invoices.put(BlobKey.of("invoices/" + invoice.id() + ".pdf"), BlobContent.ofBytes(invoice.pdf()));
        events.publish(OutgoingMessage.builder(invoice.json()).attribute("type", "invoiced").build());
    }
}

@Component
class Billing {
    @HalyxListener("billing")
    void onOrder(String json) {       // return = ack, throw = retry with backoff
        bill(json);
    }
}
```

The `orders` database is a regular `DataSource` bean, so JPA, `JdbcTemplate` and Flyway use it unchanged.

To run the same service on Azure, change the provider to `type: azure`, add `halyx-azure`, and point
the resources at Azure Blob Storage, Service Bus and Azure Database. The Java code doesn't change.

You also get health checks, Micrometer metrics and tracing, `@Cacheable` support, and
`spring.config.import=halyx:<store>` for parameters and secrets. See the
[Spring Boot guide](https://halyx-dev.github.io/halyx/spring-boot.html).

## Using it without Spring

```java
try (Halyx halyx = Halyx.builder()
        .register("aws", ProviderConfig.of("aws").region("eu-west-2"))
        .register("gcp", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, "acme-prod"))
        .build()) {

    BlobStore invoices = halyx.provider("aws").blobStore("acme-invoices");   // Amazon S3
    Publisher events = halyx.provider("gcp").publisher("order-events");     // Google Cloud Pub/Sub

    invoices.put(BlobKey.of("2026/42.pdf"), BlobContent.ofFile(pdf));
    events.publish(OutgoingMessage.builder(json).attribute("type", "invoiced").build());

    try (Blob blob = invoices.get(BlobKey.of("2026/41.pdf"))) {
        blob.content().transferTo(out);
    } catch (NotFoundException e) {
        // the same exception on S3, Azure Blob Storage and Cloud Storage
    }
}
```

Add `halyx-core` plus the provider modules (`halyx-aws`, `halyx-azure`, `halyx-gcp`, `halyx-vault`)
to your build, ideally through the `halyx-bom`; add `halyx-reactor` for `Mono` and `Flux` views of every service. See [Getting started](https://halyx-dev.github.io/halyx/getting-started.html).

## Migrating existing microservices with AI

Moving a Spring Boot service from a cloud's SDK to Halyx is mostly mechanical, which suits an AI coding assistant.
The repository includes a [Claude Code](https://claude.com/claude-code) skill for it,
[`.claude/skills/migrate-to-halyx`](.claude/skills/migrate-to-halyx). It covers Spring Cloud AWS, Spring Cloud
Azure, Spring Cloud GCP and the raw SDKs: `S3Template`, `@SqsListener`, `DynamoDbTemplate`, `@ServiceBusListener`,
`CosmosRepository`, `PubSubTemplate`, `aws-parameterstore:`, `sm://` and the rest.

Copy the folder into your service's repository (or `~/.claude/skills`), then ask Claude Code to migrate the
service to Halyx, or run `/migrate-to-halyx`. It works in reviewable steps:

1. **Inventory:** every cloud SDK use, template, listener, property and config import, each with its Halyx
   equivalent and a risk.
2. **Plan:** what maps directly and what behaves differently (ordering, delays, decimals, event formats). It checks
   whether Halyx can read your existing tables, containers and parameters as they're stored, and lists what has no
   portable equivalent and stays on the native client. It stops here for your approval.
3. **Migrate one area at a time:** configuration, storage, documents, messaging, events, streams, caches and SQL.
   Your existing tests keep passing, and memory-provider tests are added.
4. **Optionally, make the cloud a Spring profile,** as [`halyx-samples/portable-orders`](halyx-samples/portable-orders)
   does.
5. **Report:** what changed, the differences to tolerate, and the follow-ups, such as IAM changes and data
   migrations.

It has been tried on one Spring Cloud service per cloud: each passed its original tests unchanged, and read the data
the old library had written. Its reference files (cloud-by-cloud mappings, known differences and test patterns) are
plain Markdown, so other
assistants can use them too: point yours at
[`SKILL.md`](.claude/skills/migrate-to-halyx/SKILL.md) as its instructions. See
[Portable microservice](https://halyx-dev.github.io/halyx/portable-service.html#migrating-an-existing-service).

## Documentation

| | |
|---|---|
| [Getting started](https://halyx-dev.github.io/halyx/getting-started.html) | dependencies, your first calls, several clouds at once, testing |
| [API guide](https://halyx-dev.github.io/halyx/api/) | every service with examples: [storage](https://halyx-dev.github.io/halyx/api/storage.html), [NoSQL](https://halyx-dev.github.io/halyx/api/nosql.html), [messaging](https://halyx-dev.github.io/halyx/api/messaging.html), [streams](https://halyx-dev.github.io/halyx/api/streams.html), [SQL](https://halyx-dev.github.io/halyx/api/sql.html), [caches](https://halyx-dev.github.io/halyx/api/caches.html), [parameters and secrets](https://halyx-dev.github.io/halyx/api/parameters.html) |
| [Errors and retries](https://halyx-dev.github.io/halyx/api/errors.html) | the portable error model, retries, throttling and interceptors |
| [Capabilities and native clients](https://halyx-dev.github.io/halyx/api/capabilities-and-unwrap.html) | provider differences, and `unwrap` for the native SDK clients |
| [Provider configuration](https://halyx-dev.github.io/halyx/providers.html) | credentials, regions, endpoints and every provider setting |
| [Spring Boot](https://halyx-dev.github.io/halyx/spring-boot.html) | the starter, properties, listeners, health, metrics, configuration import, [Spring Data repositories](https://halyx-dev.github.io/halyx/spring-boot.html#spring-data-repositories), R2DBC and Spring for Apache Kafka |
| [Portable microservice](https://halyx-dev.github.io/halyx/portable-service.html) | a sample Spring Boot service that runs unchanged on every cloud, and how it's tested |
| [Design, build and test](https://halyx-dev.github.io/halyx/design.html) | architecture, building, the conformance kits and benchmarks |
| [Cloud testing](https://halyx-dev.github.io/halyx/cloud-testing/) | results on real [AWS](https://halyx-dev.github.io/halyx/cloud-testing/aws.html), [Google Cloud](https://halyx-dev.github.io/halyx/cloud-testing/gcp.html) and [Azure](https://halyx-dev.github.io/halyx/cloud-testing/azure.html) |
| [Roadmap](https://halyx-dev.github.io/halyx/roadmap.html) | what's done and what's next |

The documentation is published at **[halyx-dev.github.io/halyx](https://halyx-dev.github.io/halyx/)**, built from the `docs/` folder.

## Building

```bash
mvn verify      # unit tests and every conformance kit against emulators (needs Docker)
```

Java 21+ and Maven 3.9+. See [Design, build and test](https://halyx-dev.github.io/halyx/design.html#build).

## License

Halyx is licensed under the [Apache License 2.0](LICENSE).
