# Tests

The aim is one set of behaviour tests that runs, unchanged, wherever the service runs: in memory, on each cloud's
emulators, from the packaged jar, and later against a real deployment. `halyx-samples/portable-orders/src/test`
has every piece below in full; copy its classes and rename them.

| Class | Runs | In |
|---|---|---|
| `<Service>Suite`, abstract | the end-to-end tests over the API | |
| `<Service>Client` | plain `java.net.http` and `dev.halyx.json.Json`, so the tests don't depend on Spring | |
| `Expectations` | what a deployment should report: its cloud, features, settings, seeded secrets, and how long asynchronous steps may take | |
| `InProcessService` | the application in the test JVM on a free port, with profiles and properties as command-line arguments | |
| `LocalDeploymentTest` | the suite on the `local` profile | Surefire |
| `AwsEmulatorIT`, `AzureEmulatorIT`, `GcpEmulatorIT` | the suite on each cloud's profile against its emulators | Failsafe |
| `ProfilesTest` | each cloud profile resolved and bound, without connecting | Surefire |
| `PackagedJarIT` | the built jar started with `java -jar` on the `local` profile | Failsafe |

## The suite

```java
@TestMethodOrder(MethodOrderer.DisplayName.class)
public abstract class OrdersSuite {
    private final String run = "e2e-" + UUID.randomUUID().toString().substring(0, 8);   // per-test customers

    protected abstract OrdersClient client();
    protected abstract Expectations expected();

    @Test
    void healthIsUp() { ... }                              // GET /actuator/health is 200 and UP

    @Test
    void placesAnOrderAndFulfilmentConfirmsIt() {
        ...                                                // then poll with a deadline, never a fixed sleep:
        awaitOrder(customer, orderId, o -> "CONFIRMED".equals(o.get("status")));
    }

    @Test
    void eventsNotifyThroughTheEventBus() {
        assumeTrue(expected().has("events"), "events are off in this deployment");   // skipped, and reported so
        ...
    }
}
```

- Test through the API only, so the same suite runs against a container or a real deployment.
- Every test uses its own ids (a run prefix), so runs and tests never see each other's data, even on a shared
  cloud.
- Asynchronous effects are awaited with a deadline from `expected().patience()`: seconds in memory, a minute on
  emulators and clouds.
- `dev.halyx.json.Json` reads whole numbers as `Long`: compare with `1L`, or through `((Number) v).longValue()`.
- A redelivery is hard to cause through the API; a client retry often exercises the same guard (post the same
  view or request id twice), so test that.
- Settings reload: the API alone can't change a setting. An emulator test can, through its own `Halyx`, with a
  short `reload-interval` in that test.
- Cover, for each flow in the plan: the happy path through every listener; reads back (newest first, 404s); each
  validation rule; each optional feature, skipped when off; a retry or redelivery doing no harm (the same idempotency
  key twice, a message handled twice); and the settings and secrets the deployment seeded arriving.
- A `/deployment` or `info` endpoint that reports the cloud and the provider behind each bean, as the sample's
  `DeploymentController` does, lets the suite check that every bean is on the one provider the profile chose.

## In memory

```java
class LocalDeploymentTest extends OrdersSuite {
    private static InProcessService service;

    @BeforeAll static void start() {
        service = new InProcessService(List.of("local", "events", "cache"), Map.of("orders.local.signing-key", "test-key"));
    }
    @AfterAll static void stop() { service.close(); }
    ...
}
```

`InProcessService` runs `new SpringApplicationBuilder(Application.class).run("--server.port=0",
"--spring.profiles.active=...", "--key=value", ...)` and reads `local.server.port`. A `bean(name, type)` method
lets a memory test seed what an operator would create, such as a second API key. Properties go in as arguments,
not `@DynamicPropertySource`: `spring.config.import` resolves while the environment is prepared, before dynamic
properties exist, so an emulator endpoint set that way is missed and the import goes to the real cloud.

The memory provider has every service but SQL. Buckets, tables, topics and queues need no creating; subscriptions
name their topic (`halyx.subscriptions.<key>.topic`), and event-bus subscriptions name the bus as their topic.
Parameter stores start empty: seed them through the bean or a `LocalDevelopment` runner.

A service whose core is SQL needs PostgreSQL even on `local`: the local test starts it in a container
(`@Testcontainers(disabledWithoutDocker = true)`) and passes its URL as the `local` profile's `spring.datasource`
settings. So does the packaged-jar test.

## On emulators

Each IT starts the containers, creates the resources as the infrastructure would, with the default names, seeds
settings and secrets, then starts the service with the cloud's profile and the emulator's endpoint and keys:

| Cloud | Containers | Provider settings |
|---|---|---|
| AWS | `floci/floci:2.2.0` on 4566 (S3, DynamoDB, SNS, SQS, EventBridge, SSM, Secrets Manager) | `AWS_REGION=us-east-1`, `halyx.providers.aws.endpoint`, `credentials.type=static` with `id`/`secret` `test`, `properties.aws.s3.path-style=true` |
| Azure | `floci/floci-az:0.14.0` on 4577, and 4578 with a self-signed keystore for Cosmos DB; `mcr.microsoft.com/azure-messaging/servicebus-emulator:2.0.1` with `mcr.microsoft.com/azure-sql-edge:2.0.0`, its entities declared in `Config.json` | `halyx.providers.azure.endpoint` (blob), `credentials.type=anonymous`, `properties.azure.cosmos.key`, `properties.azure.servicebus.connection-string`, and on the parameter stores `azure.appconfig.connection-string` and `azure.emulator.insecure-http=true`; the system property `COSMOS.EMULATOR_SERVER_CERTIFICATE_VALIDATION_DISABLED=true` |
| Google Cloud | `floci/floci-gcp:0.10.0` on 4588 (Cloud Storage, Firestore, Pub/Sub, Secret Manager) | `GOOGLE_CLOUD_PROJECT`, `halyx.providers.gcp.endpoint`, `credentials.type=anonymous`; settings from Secret Manager (`halyx.parameter-stores.<key>.kind=secrets`), since Parameter Manager has no emulator |
| Shared | `postgres:17-alpine`, `valkey/valkey:9.1.2-alpine`, `apache/kafka:3.9.1` (`org.testcontainers.kafka.KafkaContainer`) | `halyx.databases.<key>` with `auth.type=password`, caches at `redis://host:port` with `auth.type=none`, stream clusters at the container's bootstrap servers with `auth.type=none` |

Copy the provisioning from the sample's `AwsResources`, `AzureEmulatorIT.provision` and `GcpEmulatorIT.provision`:
- **AWS:** the bucket; the DynamoDB table with string `pk` and `sk`; the SNS topic subscribed to the SQS queue with
  `RawMessageDelivery`; the EventBridge bus, a rule with the event pattern, and the queue as its target.
- **Azure:** the Cosmos DB database and container partitioned on `/pk` with default TTL -1; the blob container;
  Service Bus topics and subscriptions in the emulator's `Config.json`.
- **Google Cloud:** the bucket, Pub/Sub topic and subscription; Firestore needs nothing.
- **Everywhere:** settings and secrets through a `Halyx` built in the test against the emulator, and Kafka topics
  through `Admin.createTopics`.
- **Indexes:** each declared index as the cloud needs it: a DynamoDB global secondary index of the index's name on
  its partition and sort attributes (string types, projection `ALL`). Cosmos DB and the Firestore emulator need
  nothing; real Firestore needs a composite index (see deploy).

**Emulator quirk:** Floci-gcp replaces the whole Firestore document when an update holds only increments
(`DocumentUpdate.builder().increment(...)` alone), dropping the other fields and the hidden sort field, so the item
falls out of queries. Real Firestore and Google's own emulator keep them. Put a `set` of an unchanging field in the
same update, with a comment saying why.

The tests' own clients use the SDKs the provider modules bring (never re-declared at test scope), or a `Halyx` and
`unwrap(...)` to reach the native client, as the sample creates its Google Cloud bucket.

Event Grid namespaces and Eventarc Advanced have no emulators: run those ITs without the events feature, and say so
in the class's Javadoc and in the report. The memory test and a real-cloud run cover them.

Use `@Testcontainers(disabledWithoutDocker = true)`, and keep emulator tests in Failsafe: their system properties
are JVM-wide and would leak into memory tests in the same JVM.

## The profiles themselves

Emulator tests override endpoints and logins, so the production profiles are never loaded as written. Resolve each
without connecting.

**A cloud profile's `halyx:` config import connects while the environment is prepared, with the machine's own
credentials**, and overriding `spring.config.import` doesn't stop it. In a trial, a profile test reached the
developer's real AWS account that way. Start every profile test with `--halyx.config-import.enabled=false`, which
makes the imports load nothing:

```java
@Configuration(proxyBeanMethods = false)
static class NoBeans {}

@ParameterizedTest
@ValueSource(strings = {"aws", "azure", "gcp"})
void cloudProfileResolves(String cloud) {
    try (var context = new SpringApplicationBuilder(NoBeans.class).web(WebApplicationType.NONE).profiles(cloud).run(
            "--halyx.config-import.enabled=false",                   // imports load nothing, connect nowhere
            "--ORDERS_CACHE_ENDPOINT=rediss://cache.example:6379",  // what each deployment sets, where there's no default
            "--GOOGLE_CLOUD_PROJECT=acme-prod")) {
        Environment env = context.getEnvironment();
        Binder.get(env).bind("halyx", dev.halyx.spring.autoconfigure.HalyxProperties.class).get();
        assertThat(env.getProperty("halyx.providers." + cloud + ".type")).isEqualTo(cloud);
        assertThat(env.getProperty("halyx.caches.idempotency.auth.type")).isEqualTo("iam");
    }
}
```

Check what a typo would break: provider types, logins (`iam`), no `credentials` (the default chain), the names that
differ per cloud, and that Boot's `spring.datasource` is absent outside `local`. Run the test once with the network
off, or watch for cloud calls, to be sure it connects nowhere.

## The packaged jar

```java
class PackagedJarIT {
    @Test
    void startsOnTheLocalProfile() throws Exception {
        Path jar = Files.list(Path.of("target"))
                .filter(p -> p.toString().endsWith(".jar") && !p.toString().endsWith("-plain.jar")).findFirst().orElseThrow();
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", jar.toString(), "--server.port=" + port, "--spring.profiles.active=local").inheritIO().start();
        try {
            // poll GET /actuator/health until 200, with a deadline
        } finally {
            process.destroy();
            process.waitFor(30, TimeUnit.SECONDS);
        }
    }
}
```

Failsafe runs after `package`, so the repackaged jar exists. This catches what only the test classpath had.

## Proving the tests can fail

Before reporting, break one thing per area and see the right test fail: a wrong resource name in one cloud's
profile, a listener that throws, a skipped idempotency check, a not-found path that returns 500. Put each back. Say in the report which mistakes were tried.
