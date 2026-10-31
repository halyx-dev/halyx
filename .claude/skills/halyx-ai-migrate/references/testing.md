# Testing a migrated service

Prove each migrated area before moving on. In order:

1. **The existing tests, unchanged.** They describe the behaviour to keep. Only change a test where it reached into
   the old SDK (mocking `S3Client`, say); keep its assertions.
2. **Tests on the `memory` provider:** fast, with no Docker and no cloud. Every Halyx service has an in-memory
   implementation, except SQL.
3. **Emulator tests:** the service's existing LocalStack, Azurite or emulator tests, pointed at Halyx with an endpoint
   override. This repository's tests use Floci (AWS, Azure, Google Cloud), the Service Bus emulator, and Kafka,
   Valkey and PostgreSQL containers, and are a good source of setup code (`halyx-samples/portable-orders/src/test`).
4. **Real clouds,** when the user asks. `cloud-testing/` shows throwaway runs on EKS, GKE and AKS.

## The memory provider

```java
@SpringBootTest(properties = {
        "halyx.providers.local.type=memory",
        "halyx.blob-stores.invoices.bucket=acme-invoices",           // buckets are created on first use
        "halyx.topics.order-events.name=orders",
        "halyx.subscriptions.billing.name=orders-billing",
        "halyx.subscriptions.billing.topic=orders",                   // the memory broker binds subscriptions to topics itself
        "halyx.caches.sessions.endpoint=redis://memory:6379",         // any redis:// endpoint
        "halyx.stream-clusters.events.bootstrap-servers=memory:9092",
})
class BillingTest { ... }
```

- With several providers in the configuration, add the `local` profile or override `halyx.providers.*` so tests
  use only `memory`.
- **The memory provider has no SQL, and Spring for Apache Kafka can't use its streams,** which run in process.
  `halyx.databases` and `halyx.spring-kafka.cluster` on `memory` fail at startup, saying so. In a `local` profile,
  keep the database on Boot's `spring.datasource` and Kafka on `spring.kafka.bootstrap-servers` (PostgreSQL and
  Kafka from docker compose or containers), and put `halyx.databases` and `halyx.spring-kafka` in the cloud profiles
  only. Halyx's own streams (`StreamProducer`, `@HalyxListener` on a stream consumer) do run in memory.
- **Event buses on `memory` have no rules or filters:** every event reaches every subscription. A consumer that
  relied on a rule to filter (EventBridge patterns, Event Grid filters, Eventarc enrollments) sees everything
  locally. Check the condition in the consumer too, or keep that test on the emulator.
- Event buses on `memory` publish to a topic named after the bus: subscribe with
  `halyx.subscriptions.<key>.topic: <bus name>`.
- Parameter stores start empty. Write what the test needs through the `ParameterStore` bean, or set the properties
  directly; a `spring.config.import` of an empty store adds nothing, so make it `optional:` in that profile.
- Functions on `memory` are handlers registered by name:
  `invoker.unwrap(InMemoryFunctions.class).register("resize-image", request -> FunctionResponse.of(...))`. Tests
  that mocked `LambdaClient` or a `RestClient` to the function register a handler instead, so the real
  `FunctionInvoker` runs. A handler that throws raises `UnavailableException`; one that throws a Halyx exception has
  it passed through, standing in for a status.
- Listeners are asynchronous: wait for the effect with a deadline, and don't sleep a fixed time.
- When there's no SQL stub: exclude Boot's `DataSourceAutoConfiguration` where the service has no database in that
  profile, or run PostgreSQL in a container.

## Emulator and container tests

**`@ServiceConnection` doesn't reach Halyx's beans.** Boot's connection details from Testcontainers
(`JdbcConnectionDetails`, `RedisConnectionDetails`, `KafkaConnectionDetails`) configure Boot's own `DataSource`,
Redis connection and Kafka settings. Halyx's `DataSource`, caches and Kafka login replace those, so a test that
kept `@ServiceConnection` would point Halyx at the production host from `application.yml`. Remove
`@ServiceConnection` from those containers and set the `halyx.*` endpoints from them instead; the assertions stay:

```java
@DynamicPropertySource
static void containers(DynamicPropertyRegistry registry) {
    registry.add("halyx.databases.orders-db.host", POSTGRES::getHost);
    registry.add("halyx.databases.orders-db.port", () -> POSTGRES.getMappedPort(5432));
    registry.add("halyx.databases.orders-db.database", POSTGRES::getDatabaseName);
    registry.add("halyx.databases.orders-db.auth.username", POSTGRES::getUsername);
    registry.add("halyx.databases.orders-db.auth.password", POSTGRES::getPassword);   // with auth.type: password in the test profile
    registry.add("halyx.stream-clusters.events.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add("halyx.cache-manager.endpoint", () -> "redis://" + VALKEY.getHost() + ":" + VALKEY.getMappedPort(6379));
}
```

A database on a cloud provider type with `auth.type: password` makes no cloud calls, so a test profile can use the
service's real provider type (with no endpoints) for the containers.

**Services with no emulator** (Event Grid namespaces, Eventarc Advanced): tests of the old code usually mock the
SDK client. After the migration, publish to a memory-provider bus and read it back through a subscription, so the
real `EventPublisher` runs. With two providers, every resource names its `provider`:

```yaml
halyx:
  providers:
    azure: { type: azure }                 # the databases, caches and Kafka on containers
    local: { type: memory }                # the bus
  databases: { bookings-db: { provider: azure, engine: postgresql, auth: { type: password } } }
  event-buses: { booking-events: { provider: local, name: bookings } }
  subscriptions: { booking-events-tap: { provider: local, name: bookings-tap, topic: bookings } }
```

Read the tap with `Subscription.receive(...)`, `CloudEvents.decode(message)` and `message.ack()`. Assertions on the
event (type, source, subject, data) carry over; only the accessors change (`type()` for `getType()`).

Point the provider at the emulator, and create the resources as the infrastructure would:

```java
"halyx.providers.aws.endpoint=" + floci,                    // or LocalStack's endpoint
"halyx.providers.aws.region=us-east-1",
"halyx.providers.aws.credentials.type=static",
"halyx.providers.aws.credentials.id=test",
"halyx.providers.aws.credentials.secret=test",
"halyx.providers.aws.properties.aws.s3.path-style=true",
```

**Config imports run before the test's dynamic properties.** `spring.config.import` (`halyx:`, as well as
`aws-parameterstore:` and the others) is resolved while Spring prepares its environment, before
`@DynamicPropertySource` values exist. An emulator endpoint set there is missed by the import, which then goes to the
default endpoint: the real cloud, with whatever credentials the machine has. Start the emulator, seed its parameters
and set the provider's endpoint and credentials as system properties (or `SpringApplicationBuilder` arguments, as
`portable-orders`' `InProcessService` does) before the context starts.

System properties are JVM-wide: an emulator test's provider settings leak into memory-provider tests that run
later in the same JVM, which then find two providers. Run emulator tests in Failsafe (`*IT`) and memory tests in
Surefire, or clear the properties in `@AfterAll`.

**Clients the test itself uses** (to provision or seed the emulator) follow the SDK's own emulator rules. The
Firestore client needs only `setEmulatorHost`: with explicit credentials as well, it speaks TLS to the emulator's
plain port. Spring Cloud GCP's storage client has no emulator switch and uses the machine's Application Default
Credentials, so baseline tests of a Spring Cloud GCP service should define their own `Storage` bean with
`NoCredentials`. A Spring Cloud Azure blob client builds its endpoint from `account-name` even when a connection
string is set, so set the endpoint too.

Functions on emulators: Floci runs Lambda functions (it needs the Docker socket), and Azure and Google Cloud
functions run in the Functions host image and under the Functions Framework, called on an anonymous provider by
URL. Halyx's `AwsFunctionConformanceIT`, `AzureFunctionConformanceIT` and `GcpFunctionConformanceIT` are working
setups. A service that doesn't own the function's code runs a stand-in with the same contract.

Azure emulators take connection strings or keys as provider and store properties (`azure.cosmos.key`,
`azure.servicebus.connection-string`, `azure.appconfig.connection-string`, and `azure.emulator.insecure-http` for
Key Vault and App Configuration). Google Cloud emulators take `endpoint` and `credentials.type: anonymous`. Some
services have no emulator (Event Grid namespaces, Eventarc Advanced, Parameter Manager): say so, and cover them in
memory and on the real cloud.

## The old service's state

The existing tests start from empty stores. A deployment starts from what the old version left, and runs beside
old instances for the length of a rollout. Add one test class that seeds that state **before the context starts**
(in `@BeforeAll`, with the containers' own clients), then starts the migrated service on it:

| Seed | As the old service left it | Then check |
|---|---|---|
| Schema | its Flyway (or Liquibase) run, and rows | the history is unchanged and the rows read as before |
| Cache entries | Spring's keys (`<cache>::<key>`) and serializer, holding stale values | reads return the database's values: misses, not errors |
| Counters and keys others read | rate counters at the limit; lifetime counters | old and new instances share them; the key, format and expiry are unchanged |
| Kafka | a record the old service handled, with the group's offset committed past it | the group carries on from the committed offset: **check the committed offset itself** with `Admin.listConsumerGroupOffsets`, since an idempotent handler hides a renamed group that reads the topic again |
| Events and messages | the old format, published as an old instance would | migrated consumers read it; other consumers still get what they expect |

Then make one deliberate mistake per area (drop `namespace: ""`, rename the consumer group, remove a rule's
old-format branch) and check that the test fails. A mistake the test doesn't notice shows the test is too weak.

## The packaged service

Every test above runs on the test classpath, which can hold what the packaged jar lacks. Start the built jar or
image at least once, on emulators or a `local` profile, before the first deployment: `java -jar target/*.jar`
with the profile's settings, or the image, as `portable-orders`' `ImageIT` does. A dependency left at test scope
shows up there as `NoClassDefFoundError` at startup. In a trial it showed up only once the service was on EKS.

## The production profile

No container or emulator test runs the cloud profile itself, so a typo there (a wrong key, an anchor that doesn't
merge, an old `spring.*` setting left behind) would first show in production. Check what it resolves to without
connecting anywhere. A `halyx:` config import in the profile would connect while the environment is prepared, with
the machine's own credentials, so switch imports off:

```java
@Configuration(proxyBeanMethods = false)
static class NoBeans {}

try (var context = new SpringApplicationBuilder(NoBeans.class).web(WebApplicationType.NONE).profiles("aws")
        .run("--halyx.config-import.enabled=false")) {
    Environment env = context.getEnvironment();
    Binder.get(env).bind("halyx", dev.halyx.spring.autoconfigure.HalyxProperties.class).get();   // binds
    assertThat(env.getProperty("halyx.databases.orders-db.auth.type")).isEqualTo("iam");
    assertThat(env.getProperty("spring.datasource.url")).isNull();                               // the old settings are gone
}
```

With `spring.profiles.default`, run it with no profile too, as a deployment that sets none would.

## Behaviour checks worth adding

- A redelivered message does no harm (handlers are idempotent).
- Not-found paths: `NotFoundException` becomes the same `404` or empty result as before.
- Conditional writes and optimistic locking still reject conflicting updates.
- Decimals and dates round-trip as the service expects (see [differences.md](differences.md)).
- Settings and secrets from the config import arrive as the same property names as before.
- For a portable service: the same tests pass under each cloud's profile (`portable-orders`'
  `PortableOrdersSuite` runs one suite against every deployment).
