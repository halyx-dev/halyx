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
- Event buses on `memory` publish to a topic named after the bus: subscribe with
  `halyx.subscriptions.<key>.topic: <bus name>`.
- Parameter stores start empty. Write what the test needs through the `ParameterStore` bean, or set the properties
  directly; a `spring.config.import` of an empty store adds nothing, so make it `optional:` in that profile.
- Listeners are asynchronous: wait for the effect with a deadline, and don't sleep a fixed time.
- When there's no SQL stub: exclude Boot's `DataSourceAutoConfiguration` where the service has no database in that
  profile, or run PostgreSQL in a container.

## Emulator tests

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

Azure emulators take connection strings or keys as provider and store properties (`azure.cosmos.key`,
`azure.servicebus.connection-string`, `azure.appconfig.connection-string`, and `azure.emulator.insecure-http` for
Key Vault and App Configuration). Google Cloud emulators take `endpoint` and `credentials.type: anonymous`. Some
services have no emulator (Event Grid namespaces, Eventarc Advanced, Parameter Manager): say so, and cover them in
memory and on the real cloud.

## Behaviour checks worth adding

- A redelivered message does no harm (handlers are idempotent).
- Not-found paths: `NotFoundException` becomes the same `404` or empty result as before.
- Conditional writes and optimistic locking still reject conflicting updates.
- Decimals and dates round-trip as the service expects (see [differences.md](differences.md)).
- Settings and secrets from the config import arrive as the same property names as before.
- For a portable service: the same tests pass under each cloud's profile (`portable-orders`'
  `PortableOrdersSuite` runs one suite against every deployment).
