# Finding a service's cloud usage

Search the whole service: `src/main`, `src/test`, the build files, `application*.yml` and `*.properties`, and any
deployment files. The patterns below are starting points; follow each hit to see which operations are used.

## Build files (`pom.xml`, `build.gradle*`)

| Cloud | Look for |
|---|---|
| AWS | `software.amazon.awssdk:*` (`s3`, `dynamodb`, `dynamodb-enhanced`, `sqs`, `sns`, `eventbridge`, `ssm`, `secretsmanager`, `lambda`, `elasticache`, `rds`, `http-auth-aws` for hand-signed IAM tokens), `io.awspring.cloud:spring-cloud-aws-*`, `software.amazon.msk:aws-msk-iam-auth`, `software.amazon.jdbc:aws-advanced-jdbc-wrapper`, `com.amazonaws:*` (SDK v1) |
| Azure | `com.azure:azure-*` (`storage-blob`, `cosmos`, `messaging-servicebus`, `messaging-eventgrid`, `messaging-eventhubs`, `security-keyvault-secrets`, `data-appconfiguration`, `identity`), `com.azure.spring:spring-cloud-azure-*`, `com.azure.spring:spring-messaging-azure-*` |
| Google Cloud | `com.google.cloud:google-cloud-*` (`storage`, `firestore`, `pubsub`, `secretmanager`, `parametermanager`, `eventarc-publishing`), `com.google.cloud:spring-cloud-gcp-*`, `com.google.cloud.sql:*-socket-factory`, `com.google.cloud.hosted.kafka:managed-kafka-auth-login-handler` |
| Any | `org.springframework.cloud:spring-cloud-stream-binder-*` (Kafka, SQS, Pub/Sub, Service Bus), `spring-kafka`, `spring-boot-starter-data-redis`, `org.testcontainers:localstack` |

## Code

```bash
# AWS SDK v2 and Spring Cloud AWS
grep -rnE "software\.amazon\.awssdk\.services\.|io\.awspring\.cloud|S3Template|S3Client|S3AsyncClient|SqsTemplate|@SqsListener|SqsClient|SnsTemplate|SnsClient|DynamoDbTemplate|DynamoDbEnhancedClient|@DynamoDbBean|EventBridgeClient|SsmClient|SecretsManagerClient" src
# AWS SDK v1 (treat like v2; its mappings are the same)
grep -rnE "com\.amazonaws\.services\." src
# Azure SDK and Spring Cloud Azure
grep -rnE "com\.azure\.(storage|cosmos|messaging|security\.keyvault|data\.appconfiguration)|com\.azure\.spring|@EnableAzureMessaging|BlobServiceClient|BlobContainerClient|CosmosRepository|CosmosTemplate|@Container\(|ServiceBusTemplate|@ServiceBusListener|ServiceBusSenderClient|ServiceBusProcessorClient|EventGridPublisherClient|EventHubProducerClient|SecretClient|ConfigurationClient" src
# Google Cloud SDK and Spring Cloud GCP
grep -rnE "com\.google\.cloud\.(storage|firestore|pubsub|secretmanager|parametermanager)|com\.google\.cloud\.spring|PubSubTemplate|PubSubSubscriberTemplate|\.subscribe\(|PubSubInboundChannelAdapter|FirestoreReactiveRepository|FirestoreTemplate|@Document\(collectionName|\"gs://|SecretManagerServiceClient|Storage\b.*getService" src
# Caches, Kafka and SQL with a cloud login
grep -rnE "RedisTemplate|StringRedisTemplate|LettuceConnectionFactory|LettuceClientConfigurationBuilderCustomizer|RedisCredentialsProvider|RedisCacheManagerBuilderCustomizer|RedisCacheConfiguration|@Cacheable|@CacheEvict|@EnableCaching|opsForValue\(\)\.increment|KafkaTemplate|@KafkaListener|groupId|IAMLoginModule|IAMClientCallbackHandler|GcpLoginCallbackHandler|aws-wrapper|azure.passwordless|cloudSqlInstance" src
# Function invocations: Lambda's client, and HTTP calls to Azure Functions and Cloud Run functions
grep -rnE "LambdaClient|LambdaAsyncClient|InvokeRequest|InvocationType|AWSLambda|x-functions-key|azurewebsites\.net/api|cloudfunctions\.net|\.run\.app|IdTokenCredentials|IdTokenProvider" src
# Events SDKs, and the clients built by hand around them
grep -rnE "EventBridgeClient|putEvents|EventGridSenderClient|EventGridReceiverClient|eventarc\.publishing|PublisherClient|PublishRequest" src
# Tests that wire containers into Boot's own beans (they won't reach Halyx's)
grep -rnE "@ServiceConnection|@MockitoBean|@MockBean" src/test
```

## Configuration

YAML nests these keys (`spring:` / `cloud:` / `aws:`), so a grep for the dotted name misses them. Read every
`application*.yml` and `bootstrap*.yml` whole, and search for the nested keys too:

```bash
grep -rnE "^\s*(aws|azure|gcp|kafka|redis|datasource|stream):|aws-parameterstore|aws-secretsmanager|sm://|sm@|azureAppConfiguration" src/main/resources
```

| Cloud | Properties and imports |
|---|---|
| AWS | `spring.cloud.aws.*` (`region.static`, `credentials.*`, `s3.*`, `sqs.*`, `sns.*`, `dynamodb.*`, `endpoint`), `spring.config.import: aws-parameterstore:...` and `aws-secretsmanager:...`, `jdbc:aws-wrapper:` URLs, `sasl.jaas.config` naming `IAMLoginModule` |
| Azure | `spring.cloud.azure.*` (`storage.blob.*`, `cosmos.*`, `servicebus.*`, `eventhubs.*`, `keyvault.secret.property-sources`, `appconfiguration.stores`), `spring.datasource.azure.passwordless-enabled`, `spring.data.redis.azure.passwordless-enabled` |
| Google Cloud | `spring.cloud.gcp.*` (`project-id`, `credentials.*`, `pubsub.*`, `firestore.*`, `storage.*`, `sql.*`), `spring.config.import: sm://` or `sm@` placeholders, `${sm://...}` values |
| Any | `spring.kafka.*` security and SASL settings, `spring.data.redis.*`, `spring.datasource.*`, `spring.cloud.stream.*` bindings |

## Also note

- **Message and event formats:** the payload format (JSON, Avro), the attributes and headers relied on, and whether
  SNS raw delivery is on. Consumers on other services depend on these.
- **Listener semantics:** acknowledgement modes, visibility timeouts and concurrency, dead-letter queues,
  FIFO/session/ordering keys, and retries.
- **Data layouts:** each document table's key attributes and types, and how items are serialised. Each blob
  store's key naming and its metadata.
- **IAM:** the permissions the service's role has today; Halyx needs a few the old code may not have
  ([differences.md](differences.md#permissions)).
- **Who else reads it:** cache entries, counters and keys that dashboards or other services read; Kafka records
  other teams consume (the value format and `JsonSerializer`'s type headers); events whose rules and consumers sit
  outside the service. Each is a contract the migration must keep, or change with its owners.
- **Hand-written cloud glue:** credential providers, token signers and client beans built for IAM logins
  (ElastiCache SigV4 tokens, Memorystore access tokens, `EventBridgeClient` or `PublisherClient` beans). Halyx
  replaces them; list them so they are deleted, not left behind.
- **Functions called:** each function's name, URL or ARN, how it logs in (IAM, a function key and where the key
  lives, an Entra ID token, a Google ID token), its timeout and retries, and whether a second run is harmless. Who
  deploys the function on each target cloud: Halyx invokes functions, it doesn't deploy them.
- **Bean names:** every `@Component`, `@Service`, `@Configuration` class (in lower camel case) and `@Bean`
  method. The `halyx.*` keys you choose must not repeat them.
