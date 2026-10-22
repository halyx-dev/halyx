# Halyx with Spring Boot: what to migrate to

The full reference is `docs/spring-boot.md`, and `docs/api/*.md` for each service; check names there before using
them. This page is the short form.

## Dependencies

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>dev.halyx</groupId>
      <artifactId>halyx-bom</artifactId>
      <version>${halyx.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency><groupId>dev.halyx</groupId><artifactId>halyx-spring-boot-starter</artifactId></dependency>
<dependency><groupId>dev.halyx</groupId><artifactId>halyx-aws</artifactId></dependency>     <!-- and/or halyx-azure, halyx-gcp -->
<!-- if the service used Spring Data repositories over DynamoDB, Cosmos DB or Firestore -->
<dependency><groupId>dev.halyx</groupId><artifactId>halyx-spring-data-document</artifactId></dependency>
<!-- if the service is reactive (WebFlux, Mono/Flux) -->
<dependency><groupId>dev.halyx</groupId><artifactId>halyx-reactor</artifactId></dependency>
```

### SDK versions

Each provider module needs the cloud SDK version Halyx was built with (the `aws-sdk.version`,
`azure-sdk-bom.version` and `gcp-libraries-bom.version` properties of `halyx-parent`'s `pom.xml`). A Spring Cloud
cloud BOM imported in the same build pins its own SDK version, and an import listed earlier wins. So while the
old library is still there, list the SDK's BOM first:

```xml
<dependency><groupId>software.amazon.awssdk</groupId><artifactId>bom</artifactId><version><!-- Halyx's --></version><type>pom</type><scope>import</scope></dependency>
<!-- Azure: com.azure:azure-sdk-bom;  Google Cloud: com.google.cloud:libraries-bom -->
<!-- then the Spring Cloud AWS, Azure or GCP BOM, until it is removed -->
```

Spring Boot's own dependency management wins for the libraries it manages. On Boot 3.5, Lettuce resolves to 6.6
(Halyx is built with 6.9), and caches work on it; check `mvn dependency:list` for anything else that resolves lower
than Halyx's `pom.xml` says.

The provider modules bring the cloud SDKs, the Redis and Valkey driver, SQL IAM login and the Kafka login, so the
service drops its own SDK dependencies. **Don't re-declare an SDK artifact with `<scope>test</scope>`** for the
tests' own clients (provisioning an emulator, reading a queue). A direct declaration wins over the transitive one,
so `software.amazon.awssdk:sqs` at test scope makes `halyx-aws`'s SQS client test-only. Every test still passes,
since the test classpath has it, and the packaged service fails at startup with `NoClassDefFoundError`. This
happened in a trial and was found only on EKS. The tests can use the clients the provider module brings. Check
with `mvn dependency:list -DincludeScope=runtime`. Their SDK clients can switch on the old library's auto-configuration while
both are present: see [differences.md](differences.md#runtime). On Google Cloud, the Cloud SQL connector needs
`com.google.cloud.sql:postgres-socket-factory` (or the MySQL one) on the runtime classpath. Spring Boot 3.5 and 4
both work.

## Providers

```yaml
halyx:
  providers:
    aws: { type: aws, region: eu-west-2 }
    # azure: { type: azure, properties: { azure.storage.account: acme, azure.cosmos.endpoint: "https://acme.documents.azure.com:443/",
    #          azure.cosmos.database: orders, azure.servicebus.namespace: acme.servicebus.windows.net } }
    # gcp:   { type: gcp, properties: { gcp.project-id: acme-prod } }
```

With one provider, resources use it without naming it. Credentials come from the default chain. Use
`credentials.type: impersonate` (an assumed role or service account) or `service-principal` (Entra ID) when the
service needs a specific identity. Endpoint overrides are `halyx.providers.<name>.endpoint`, for emulators.

## Resources, beans and code

Each key under these groups is a bean name; inject with `@Qualifier("<key>")`. The key also defaults the cloud
resource's name, but set `bucket`, `table` or `name` explicitly when migrating, so the resource stays the same.

| Group | Bean | Key methods |
|---|---|---|
| `halyx.blob-stores.<key>: { bucket }` | `BlobStore` | `put(BlobKey, BlobContent[, PutOptions])`, `get` (a `Blob`, closeable), `getBytes`, `head`, `exists`, `delete`, `listAll(prefix)`, `list(ListOptions)`, `copy`, `presignDownload(key, Duration)`, `presignUpload` |
| `halyx.document-stores.<key>: { table, partition-key, sort-key, indexes }` | `DocumentStore`, and Spring Data `DocumentRepository<T, ID>` beans | `get(Key)`, `put(Key, Document[, WriteOptions])`, `update(Key, DocumentUpdate)`, `delete`, `query`, `queryIndex`, `transact` |
| `halyx.topics.<key>: { name }` | `Publisher` | `publish(String)`, `publish(OutgoingMessage)`, `publishAll` |
| `halyx.subscriptions.<key>: { name, listener: {...} }` | `Subscription`, used by `@HalyxListener("<key>")` | `receive(max, wait)`, `listen(...)` |
| `halyx.event-buses.<key>: { name }` | `EventPublisher` | `publish(CloudEvent)`, `publishAll` |
| `halyx.stream-clusters.<key>: { bootstrap-servers, auth }`, `halyx.stream-producers.<key>`, `halyx.stream-consumers.<key>` | `StreamProducer`, `StreamConsumer` | `send(key, value)`, `send(OutgoingRecord)`; consumers through `@HalyxListener` |
| `halyx.caches.<key>: { endpoint, auth, namespace, default-ttl, codec }` | `Cache<V>` | `get`, `put(key, value[, ttl])`, `putIfAbsent`, `delete`, `increment(key, delta[, ttl])`, `getOrLoad`, `tryLock`. Keys are `<namespace>:<key>`, the namespace defaulting to the bean name: set `namespace: ""` to keep existing keys |
| `halyx.cache-manager: { endpoint, auth, default-ttl, ttl, codec, trusted-packages }` | Spring `CacheManager` | `@Cacheable`, `@CachePut`, `@CacheEvict` unchanged. `codec: jdk` (the default, as Spring's) reads any class unless `trusted-packages` is set: set it. `json` requires it |
| `halyx.parameter-stores.<key>: { kind: secrets\|parameters, prefix, location }` | `ParameterStore` | `get`, `require(name[, field])`, `getAll(prefix)`, `put`, `watch` |
| `halyx.databases.<key>: { engine, host, database, auth: { type: iam\|password, username } }` | `DataSource` (primary if only one) | JPA, `JdbcTemplate`, Flyway and Liquibase unchanged |
| `halyx.r2dbc-databases.<key>` | R2DBC `ConnectionFactory` | Spring Data R2DBC unchanged |
| `halyx.spring-kafka.cluster: <stream cluster key>` | (configures Spring Kafka's factories) | `KafkaTemplate` and `@KafkaListener` unchanged |

Every key is a bean name, in every group, including `databases` and `stream-clusters`. Before choosing them, list
the service's own bean names (each `@Component`, `@Service` and `@Configuration` class, in lower camel case, and
each `@Bean` method). A class `Bookings` and `halyx.databases.bookings` clash; `bookings-db` doesn't.

Imports: `dev.halyx.spring.messaging.HalyxListener`; `dev.halyx.cache.Cache`; `dev.halyx.events.CloudEvent`,
`EventPublisher` and `CloudEvents`; `dev.halyx.messaging.Publisher`, `Subscription`, `ReceivedMessage` and
`OutgoingMessage`; `dev.halyx.storage.BlobStore`; `dev.halyx.nosql.DocumentStore`;
`dev.halyx.parameters.ParameterStore`; and, for configuration tests, `dev.halyx.spring.autoconfigure.HalyxProperties`.

```java
@Component
class OrderFulfilment {                               // not "Fulfilment": bean "fulfilment" is the subscription
    @HalyxListener("fulfilment")                      // a halyx.subscriptions or halyx.stream-consumers key
    void onOrder(String body) { ... }                 // or byte[], CloudEvent, ReceivedMessage, or ReceivedRecord on streams
}                                                     // return = ack (commit on streams); throw = redeliver with backoff
```

```yaml
spring:
  config:
    import: halyx:app-secrets                         # names become properties: db/password -> db.password
halyx:
  parameter-stores:
    app-secrets: { kind: secrets, prefix: orders-svc, reload-interval: 1m }
```

Messages: `OutgoingMessage.builder(body).attribute(k, v).orderingKey(k).deduplicationId(id).delay(d).build()`.
Received: `bodyAsString()`, `body()`, `attribute(name)`, `orderingKey()`, `deliveryAttempt()`, `ack()`, `nack()`,
`nack(Duration)`. Records: `OutgoingRecord.builder(value).key(k).header(n, v)`, and on receipt `key()`,
`valueAsString()`, `header(name)`, `partition()`, `offset()`. Events:
`CloudEvent.builder(type, source).subject(s).json(data).extension(n, v).build()`.

Spring Data entities are records, so mutable entity classes (with setters, as `@DynamoDbBean`, Cosmos and Firestore
entities usually are) change shape. Updates become copies (`invoice.withStatus("BILLED")`, saved again) or partial
updates through `DocumentStore.update`. Keep the component names equal to the stored attribute names. Annotate them
with `@DocumentTable("<document store key>")`, `@PartitionKey`, `@SortKey`, and
`@org.springframework.data.annotation.Version dev.halyx.nosql.Version` for optimistic locking. Halyx's repositories coexist with other Spring Data modules (JPA, JDBC,
Spring Data Cosmos, Spring Cloud GCP's Firestore and so on): each claims only its own repositories, so entities can
move one at a time. Derived queries work on the partition key, the sort key and declared indexes; anything that would scan the table fails at startup
(`docs/spring-boot.md#spring-data-repositories`).

## Errors, capabilities and native clients

- Every failure is an unchecked `CloudException`: `NotFoundException`, `AlreadyExistsException`,
  `PreconditionFailedException`, `AccessDeniedException`, `InvalidArgumentException`, `UnsupportedFeatureException`,
  `ThrottledException` and `UnavailableException` (both retryable), `DataIntegrityException`,
  `UnknownCloudException`. Replace SDK exception handling (`NoSuchKeyException`, `BlobStorageException` with a 404,
  `ConditionalCheckFailedException` and so on) with these.
- Optional features: `capabilities().supports(MessagingCapability.DELAYED_DELIVERY)` and so on, or `require(...)`
  to fail fast (`docs/api/capabilities-and-unwrap.md`).
- A feature with no portable form: `store.unwrap(S3Client.class)` and the like. Keep such code in one class, named
  for the feature, and note it as cloud-specific.

## Health, metrics and tracing

The starter adds a `halyx` health contributor, Micrometer observations and trace propagation through messages,
events and records. Remove hand-written health checks and tracing glue for the migrated clients.
