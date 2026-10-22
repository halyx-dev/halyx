# Portability rules

Each rule has an id, what to look for, how to find candidates, why it matters, the usual severity, and the fix.
Read every hit before recording it (see SKILL.md). "All clouds" means AWS, Azure and Google Cloud.

## C: code

### C1. Native clients outside one isolated class
- **Find:** `grep -rnE "\.unwrap\(|software\.amazon\.awssdk|com\.azure\.|com\.google\.cloud\." src/main | grep -v "com\.google\.cloud\.sql"`
- **Why:** `unwrap` returns a different class on each cloud (`S3Client`, `BlobContainerClient`, `Storage`). Code
  using it runs on one cloud only. SDK imports elsewhere mean a cloud's API is used directly.
- **Fine when:** the use sits in one class named for the feature, which checks the provider or a capability first
  and is documented as cloud-specific (for example, a `S3SelectReports` class only wired in the `aws` profile).
- **Severity:** blocker for the other clouds when it runs unconditionally; hygiene when isolated and guarded.
- **Fix:** move it behind an interface with one implementation per cloud, each `@Profile`'d, or replace it with the
  Halyx API.

### C2. Leftover cloud SDK exception handling
- **Find:** `grep -rnE "NoSuchKeyException|ConditionalCheckFailedException|BlobStorageException|CosmosException|ServiceBusException|ApiException|StatusRuntimeException|AwsServiceException|SdkException" src/main`
- **Why:** Halyx throws its own unchecked exceptions (`NotFoundException`, `PreconditionFailedException`,
  `AlreadyExistsException`, `ThrottledException`, `UnavailableException` and the rest). A catch on an SDK type never
  fires, so not-found and conflict paths silently change, on every cloud.
- **Severity:** risk.
- **Fix:** catch the Halyx exception.

### C3. Messaging features one cloud lacks
- **Find:** `grep -rnE "\.delay\(|deduplicationId\(|orderingKey\(|nack\([^)]" src/main`
- **Why:** from `MessagingCapability`:
  - `DELAYED_DELIVERY` is supported on SQS queues and Service Bus, but not on SNS or Pub/Sub.
  - `DEDUPLICATION` needs FIFO queues and topics, or Service Bus duplicate detection; Pub/Sub has none.
  - `ORDERING` needs FIFO, Service Bus sessions, or ordering-enabled Pub/Sub subscriptions.
  - `NACK_DELAY`: `nack(Duration)` redelivers at once on Service Bus.

  Unsupported features fail with `UnsupportedFeatureException`, except `nack(delay)`, which quietly redelivers at
  once.
- **Severity:** blocker where the call fails on a target cloud; risk for `nack(delay)` on Azure.
- **Fix:** check `capabilities().supports(...)` and fall back, or keep the consumer idempotent and drop the
  feature. For ordering and deduplication, record which entities must be created FIFO, session-enabled or
  ordering-enabled on each cloud.

### C4. Presigned URLs
- **Find:** `grep -rnE "presignDownload|presignUpload|presignMultipart" src/main`
- **Why:** on Google Cloud, `PRESIGNED_URL` and `PRESIGNED_MULTIPART` hold only with service-account
  credentials, which can sign. User credentials (a gcloud user login) can't, so the capability is absent and the
  call fails. Workload Identity and the metadata server sign through the IAM `signBlob` API, which needs
  `iam.serviceAccounts.signBlob` on the service account.
- **Severity:** risk on Google Cloud.
- **Fix:** check the capability, and grant the service account `roles/iam.serviceAccountTokenCreator` on itself,
  or proxy the download.

### C5. Transactional stream producers
- **Find:** `transactional: true` under `halyx.stream-producers`, or `StreamProducerConfig...transactional(true)` in
  code.
- **Why:** `StreamCapability.TRANSACTIONS` holds on MSK and Managed Service for Apache Kafka, but not on Event
  Hubs, which offers transactions only on premium and dedicated tiers, as a preview. The producer fails when it's
  created. Idempotent producers (`idempotent: true`) are fine everywhere, Event Hubs included.
- **Severity:** blocker on Azure.
- **Fix:** keep consumers idempotent and turn transactions off in the `azure` profile, or don't target Event Hubs
  for this stream.

### C6. Decimals compared or round-tripped by value
- **Find:** `BigDecimal` fields in document entities (`@DocumentTable` records) and in data read from a
  `DocumentStore`, then `grep -rnE "\.equals\(|toString\(\)|hashCode\(\)" src/main/java` on those fields
  (for example `total().equals(`); and signatures or hashes computed over amounts.
- **Why:** no document store keeps a decimal's scale: `3.50` reads back as `3.5`. Firestore stores doubles, and so
  do Spring Data repositories on Cosmos DB.
- **Severity:** risk (money and signatures over amounts are where it bites).
- **Fix:** `compareTo`, explicit formatting (`setScale(2)`, `toPlainString()`), or minor units as `long`.

### C7. Large payloads
- **Find:** messages or events built from documents, files or lists; publishers without `claim-check`.
- **Why:** the largest message is 256 KiB on SQS, SNS and Service Bus standard, and 10 MB on Pub/Sub. The largest
  event is about 256 KB on EventBridge, and about 1 MB on Event Grid and Eventarc. What fits on one cloud fails on
  another.
- **Severity:** risk where payloads can grow.
- **Fix:** `halyx.topics.<key>.claim-check: { blob-store, threshold }`, or send a reference.

### C8. Consumers that rely on routing to filter
- **Find:** `@HalyxListener` methods on event subscriptions that don't check the event's type or fields.
- **Why:** EventBridge patterns, Event Grid filters and Eventarc enrollments do the filtering in the cloud.
  Equivalent filters must exist on every cloud, and the memory provider's buses have none, so a `local` profile
  delivers everything.
- **Severity:** risk on any cloud where the filter isn't recreated; hygiene for `local`.
- **Fix:** check the condition in the consumer too, and list the filter each cloud needs.

### C9. Cloud names in code
- **Find:** `grep -rnE "arn:aws|amazonaws\.com|\.windows\.net|\.azure\.(com|net)|googleapis\.com|projects/[a-z]|\.cloud\.goog" src/main`
- **Why:** resource names, ARNs and endpoints in code tie it to one account and one cloud.
- **Severity:** blocker for other clouds if used at runtime.
- **Fix:** move them into `halyx.*` properties in the cloud's profile.

### C10. Bean names
- **Find:** every `halyx.*` key against the service's bean names: each `@Component`, `@Service`, `@Repository`,
  `@Controller` and `@Configuration` class in lower camel case, and each `@Bean` method.
- **Why:** every `halyx.*` key, in every group, is a bean name. A clash fails at startup, naming both.
- **Severity:** blocker (on every cloud, so usually found already; check keys that only some profiles define).
- **Fix:** rename the key, such as `bookings-db` for `bookings`.

## G: configuration

### G1. Cloud settings in the shared configuration
- **Find:** the documents of `application.yml` without `spring.config.activate.on-profile`, and plain
  `application.properties`: provider types, `region`, `properties.aws.*`, `properties.azure.*`,
  `properties.gcp.*`, endpoints, and `rediss://` and `*.servicebus.windows.net` hosts.
- **Why:** shared settings apply to every cloud. An AWS region or an ElastiCache cache name in the base document
  is wrong on Azure.
- **Severity:** blocker when it breaks another cloud; hygiene otherwise.
- **Fix:** the shared resources (keys, names from environment variables) in the base document, and each cloud's
  provider and settings in its own profile document. `halyx-samples/portable-orders`' `application.yml` shows the
  shape.

### G2. A profile per target cloud, and a default
- **Find:** `on-profile: aws|azure|gcp` documents; `spring.profiles.default`.
- **Why:** a cloud without a profile has no provider and no names, so it can't run. Without
  `spring.profiles.default`, a deployment that sets no profile gets none.
- **Severity:** blocker for a cloud with no profile; hygiene for the default.
- **Fix:** add the profile with the provider and the cloud's resource settings, and set the default to today's
  cloud.

### G3. Resource naming that differs per cloud
- **Find:** `halyx.subscriptions.*.name`, `halyx.event-buses.*.name`, and their `topic` settings.
- **Why:** the names have different shapes on each cloud:
  - Service Bus subscriptions are `<topic>/subscriptions/<name>`.
  - Eventarc buses are `projects/<p>/locations/<l>/messageBuses/<id>`.
  - Event Grid needs `properties.azure.eventgrid.endpoint`.
  - SNS topics named, not given as ARNs, need `sns:ListTopics`.

  One value can't be right on every cloud.
- **Severity:** blocker when a shared value only fits one cloud.
- **Fix:** set the name per profile, or from an environment variable each deployment sets.

### G4. Settings that Halyx's beans make ineffective
- **Find:** `spring.datasource.*` next to `halyx.databases`, `spring.cache.*` next to `halyx.cache-manager`,
  `spring.data.redis.*` next to Halyx caches, and `spring.kafka.bootstrap-servers` or `security`/`sasl` settings
  next to `halyx.spring-kafka`.
- **Why:** the Halyx bean wins, so the Spring setting is ignored (Halyx logs a warning for the first two). It
  misleads readers, and on another cloud someone edits the setting that does nothing.
- **Severity:** hygiene.
- **Fix:** remove them, moving pool settings to `halyx.databases.<key>.pool`.

### G5. Credentials in configuration
- **Find:** `credentials: { type: static ... }`, `access-key`, `secret`, `password:`, connection strings, or
  `azure.*.key` in profiles that real deployments use.
- **Why:** keys in configuration tie a deployment to one account and are a secret to rotate. Each cloud's default
  chain covers IAM roles, workload identity, managed identities and gcloud.
- **Severity:** risk (security); fine for emulator profiles.
- **Fix:** remove them and grant the workload identity, or read the password from a parameter store
  (`auth.secret`).

### G6. A profile without a cloud
- **Find:** a `local` (or test) profile on `type: memory`, and `halyx.databases` or `halyx.spring-kafka.cluster`
  defined where that profile sees them.
- **Why:** the memory provider has no SQL, and Spring for Apache Kafka can't use its in-process streams. Startup
  fails, saying so.
- **Severity:** blocker for that profile.
- **Fix:** keep `halyx.databases` and `halyx.spring-kafka` in the cloud profiles; give `local` Boot's
  `spring.datasource` and `spring.kafka.bootstrap-servers`.

### G7. Caches and their keys
- **Find:** `halyx.cache-manager.codec: jdk` (or no codec) without `trusted-packages`; `halyx.caches.*` without
  `namespace`.
- **Why:**
  - The JDK codec reads any class from Redis unless `trusted-packages` limits it.
  - A cache's namespace defaults to its bean name. Moving a cache between configurations, or sharing keys with
    other code, silently changes the keys.
- **Severity:** risk for the codec (deserialization of untrusted data); hygiene for the namespace unless keys are
  shared.
- **Fix:** set `trusted-packages`; set `namespace` explicitly where keys matter.

## B: build

### B1. Provider modules for each target cloud
- **Find:** `dev.halyx:halyx-aws`, `halyx-azure`, `halyx-gcp` in the build.
- **Why:** a profile for a cloud whose module is missing fails at startup with "unknown provider type".
- **Severity:** blocker for that cloud.
- **Fix:** add the module, or build one artifact per cloud and say so.

### B2. Leftover cloud libraries
- **Find:** `io.awspring.cloud`, `com.azure.spring`, `com.google.cloud:spring-cloud-gcp-*`, `aws-msk-iam-auth`,
  `aws-advanced-jdbc-wrapper`, `managed-kafka-auth-login-handler`, and direct `software.amazon.awssdk`, `com.azure`
  or `com.google.cloud` artifacts.
- **Why:**
  - Spring Cloud libraries auto-configure clients for whatever SDKs they find; Spring Cloud GCP fails at startup
    once `halyx-gcp` is present.
  - Their BOMs pin older SDKs than Halyx needs.
- **Severity:** risk; blocker where startup fails.
- **Fix:** remove them, or switch off their auto-configuration until their last use goes.

### B3. SDK artifacts at test scope
- **Find:** `software.amazon.awssdk`, `com.azure` or `com.google.cloud` dependencies with `<scope>test</scope>`, and
  `mvn dependency:list -DincludeScope=runtime` missing an SDK client the provider module needs.
- **Why:** a direct test-scope declaration demotes the provider module's compile dependency on the same artifact.
  Every test passes, and the packaged service fails with `NoClassDefFoundError`. This happened in a trial and was
  found only on EKS.
- **Severity:** blocker.
- **Fix:** remove the declaration; the tests can use the clients the provider module brings.

### B4. Cloud-specific runtime pieces
- **Find:** `halyx.databases.*.properties.gcp.sql.instance` without `com.google.cloud.sql:postgres-socket-factory`
  (or the MySQL one) at runtime.
- **Why:** the Cloud SQL connector is the service's dependency.
- **Severity:** blocker on Google Cloud.
- **Fix:** add it at runtime scope.

## D: deployment

### D1. Permissions Halyx needs beyond the old code's
- **Find:** the IAM policies, role definitions and Terraform in the repository, against what the service uses.
- **Why:** Halyx needs a few permissions the service's own calls don't:
  - **parameter store health:** reads `<prefix>/halyx-ping` and expects "not found", so grant reads under the
    whole prefix (`secret:<prefix>/*`); a policy on single secrets turns health DOWN
  - **blob store health:** `s3:ListBucket`, or the equivalent
  - **SNS topics given by name:** `sns:ListTopics`
  - **SQS:** `GetQueueUrl` and `GetQueueAttributes`, alongside receive, delete and change-visibility
  - **config import with reload:** `ListSecrets` and `BatchGetSecretValue`
  - **Memorystore:** `roles/memorystore.dbConnectionUser`
  - **Azure caches:** a data access policy assignment
- **Severity:** blocker where it fails; risk where only health is affected.
- **Fix:** grant them, scoped to the service's resources.

### D2. Workload identity per cloud
- **Find:** the deployment's service account and pod settings.
- **Why:** each cloud's default chain finds the platform's identity:
  - **EKS:** a Pod Identity association, or the IRSA annotation.
  - **GKE:** `iam.gke.io/gcp-service-account`.
  - **AKS:** `azure.workload.identity/client-id`, and the pod label `azure.workload.identity/use: "true"`.

  A user-assigned Azure identity is found through `AZURE_CLIENT_ID`, which Workload ID sets. A chart that sets
  only one cloud's binding doesn't deploy to the others.
- **Severity:** blocker for the clouds it doesn't cover.
- **Fix:** values per cloud for the service account annotations and pod labels.

### D3. Buildpack images and direct memory
- **Find:** images built with buildpacks (`spring-boot:build-image`, Paketo) and no `-XX:MaxDirectMemorySize`.
- **Why:** the buildpack's memory calculator caps direct memory at 10 MB, which the cloud SDKs' Netty clients
  exceed under load. Seen on AKS with the Azure SDK.
- **Severity:** risk.
- **Fix:** `JAVA_TOOL_OPTIONS=-XX:MaxDirectMemorySize=64M` or more.

### D4. Services with no Halyx equivalent, or no form on another cloud
- **Find:** the infrastructure and code for Kinesis, Step Functions, SES, DynamoDB Streams, Cosmos DB change feed,
  Event Grid basic topics, Spanner, Bigtable, BigQuery, Cloud Tasks, and the like.
- **Why:** these stay on the native SDK. Event Grid basic topics are not namespace topics: Halyx publishes only to
  namespaces.
- **Severity:** blocker for the other clouds, unless there's a per-cloud replacement.
- **Fix:** list each, with its counterpart on each target cloud or a statement that the feature is single-cloud.

## T: tests

### T1. Tests per cloud
- **Find:** emulator or container tests and the profiles they activate.
- **Why:** a service whose tests only ever run one cloud's profile has never shown it runs on the others.
- **Severity:** risk.
- **Fix:** run the same tests under each cloud's profile, on emulators (Floci for AWS, Azure and Google Cloud, plus
  the Service Bus emulator), as `portable-orders`' `PortableOrdersSuite` does.

### T2. Container tests wired to Boot, not Halyx
- **Find:** `@ServiceConnection` on PostgreSQL, Redis or Valkey, and Kafka containers in a service that uses
  `halyx.databases`, Halyx caches or `halyx.spring-kafka`.
- **Why:** `@ServiceConnection` configures Boot's beans, so Halyx's use the hosts from `application.yml`.
- **Severity:** risk (the tests don't test what runs).
- **Fix:** set the `halyx.*` endpoints from the containers with `@DynamicPropertySource`.

### T3. The cloud profiles themselves
- **Find:** any test that loads the `aws`, `azure` and `gcp` profiles.
- **Why:** emulator tests override the endpoints and logins, so a typo in a production profile shows first in
  production.
- **Severity:** hygiene.
- **Fix:** a test that resolves each profile's environment without connecting
  (`--halyx.config-import.enabled=false`, so a `halyx:` import doesn't reach the cloud with the machine's
  credentials), binds it to `dev.halyx.spring.autoconfigure.HalyxProperties`, and asserts the logins and names.

### T4. The packaged service
- **Find:** any test or CI step that starts the built jar or image.
- **Why:** the test classpath can hold what the package lacks (B3).
- **Severity:** hygiene.
- **Fix:** start it once on emulators or the `local` profile, as `portable-orders`' `ImageIT` does.
