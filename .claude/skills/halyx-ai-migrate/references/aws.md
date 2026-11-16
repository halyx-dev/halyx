# AWS to Halyx

From Spring Cloud AWS 3.x (`io.awspring.cloud`) and the AWS SDK v2 (v1 maps the same way). Use provider type `aws`.
Resources keep their names: bucket, table, topic and queue names stay as they are.

## Configuration

| Before | After |
|---|---|
| `spring.cloud.aws.region.static: eu-west-2` | `halyx.providers.aws.region: eu-west-2` |
| `spring.cloud.aws.credentials.access-key` / `secret-key` | Prefer the default chain (drop them). If keys are required: `halyx.providers.aws.credentials: { type: static, id, secret }`, fed from a secret |
| an assumed role (`StsAssumeRoleCredentialsProvider`) | `credentials: { type: impersonate, target: <role ARN>, external-id, session-name }` |
| `spring.cloud.aws.endpoint`, `spring.cloud.aws.<service>.endpoint` (LocalStack) | `halyx.providers.aws.endpoint`, plus `properties.aws.s3.path-style: true` for S3 on emulators |
| `spring.config.import: aws-parameterstore:/config/orders/` | `spring.config.import: halyx:settings`, with `halyx.parameter-stores.settings: { kind: parameters, prefix: config/orders }` |
| `spring.config.import: aws-secretsmanager:/secret/orders` | `halyx:secrets`, with `halyx.parameter-stores.secrets: { kind: secrets, prefix: secret/orders }`. JSON secrets expand into fields (`db.password`) |
| `PutParameter` with `KeyId` (a `SecureString` on a customer-managed key), `CreateSecret` with `KmsKeyId` | `server-side-key: { store, name }` on the parameter store, a key of `halyx.key-stores` (see [Keys](#keys-kms)). SSM needs `properties: { aws.ssm.secure: true }`. Reads need nothing: the service decrypts |

Halyx's portable names are segments separated by `/` (`orders/db/password` is the SSM parameter
`/orders/db/password`). Existing names that don't fit, such as dots or underscores, need `native-names: true` on the
store; property keys then follow the native names. Check them before choosing.

## Object storage: S3

```java
// Before: S3Template or S3Client
s3Template.upload("acme-invoices", "2026/42.pdf", inputStream);
S3Resource r = s3Template.download("acme-invoices", "2026/42.pdf");
s3Client.getObject(b -> b.bucket("acme-invoices").key(k), ResponseTransformer.toBytes());
s3Template.deleteObject("acme-invoices", k);
URL url = s3Template.createSignedGetURL("acme-invoices", k, Duration.ofMinutes(10));

// After: halyx.blob-stores.invoices.bucket: acme-invoices
@Qualifier("invoices") BlobStore invoices;
invoices.put(BlobKey.of("2026/42.pdf"), BlobContent.ofStream(inputStream, length));
try (Blob blob = invoices.get(BlobKey.of("2026/42.pdf"))) { ... }
byte[] bytes = invoices.getBytes(key);
invoices.delete(key);
URI uri = invoices.presignDownload(key, Duration.ofMinutes(10)).uri();
```

`NoSuchKeyException` and a `404` become `NotFoundException`. Content type, metadata, conditional writes (`ifMatch`,
`ifNotExists`) and storage classes go in `PutOptions`. `listObjectsV2` paging becomes `list(ListOptions)` or
`listAll(prefix)`.

**SSE-KMS:** `serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(...)` on puts, multipart uploads and
copies (or `x-amz-server-side-encryption` headers on presigned uploads) becomes `server-side-key: { store, name }` on
the blob store, or `PutOptions.builder().serverSideKey(key)` for one write. Presigned uploads carry the headers in
`PresignedUrl.headers()`, which the client must send. Existing objects need nothing: S3 decrypts them. Code that
relied on the bucket's default encryption changes nothing. The **S3 Encryption Client** (client-side) has its own
object format, which Halyx's `encryptedWith(key)` view doesn't read: see
[differences.md](differences.md#existing-data).

## Documents: DynamoDB

| Before | After |
|---|---|
| `DynamoDbTemplate` or `DynamoDbEnhancedClient` with `@DynamoDbBean` classes | a record with `@DocumentTable`, `@PartitionKey` and `@SortKey`, and a `DocumentRepository<Order, Key>` (`String` id without a sort key) |
| `dynamoDbTemplate.save(order)`, `load(key, Order.class)`, `delete(...)` | `repository.save`, `findById`, `deleteById` |
| `query(QueryEnhancedRequest...)` on the partition key | a derived query: `findByCustomerIdOrderByOrderIdDesc(...)` |
| a global secondary index query | declare the index under `halyx.document-stores.<key>.indexes`, then derive `findBy<IndexAttribute>...` |
| `@DynamoDbVersionAttribute` | `@Version dev.halyx.nosql.Version` (Halyx stores it as `halyx_version`) |
| conditional writes, `ConditionalCheckFailedException` | `WriteOptions.ifNotExists()` and `ifVersion(...)`; `PreconditionFailedException` (repositories: `DuplicateKeyException`, `OptimisticLockingFailureException`) |
| `transactWriteItems` | `DocumentStore.transact(...)` |
| `UpdateItem` with an update expression | `DocumentStore.update(key, DocumentUpdate.builder().set(...).increment(...).build())` |

**Existing tables:** find the table's real name first. Spring Cloud AWS's `DynamoDbTemplate` names a table after
the entity class in snake case (`Invoice` is `invoice`, `OrderLine` is `order_line`), with
`spring.cloud.aws.dynamodb.table-prefix` and `table-suffix`, unless a custom `DynamoDbTableNameResolver` is
registered. Set that name as `table`. Then set `partition-key` and `sort-key` to the table's key attribute names
(the `@DynamoDbPartitionKey` and `@DynamoDbSortKey` properties, unless `@DynamoDbAttribute` renames them). Keys must be strings
(numeric DynamoDB keys need a data migration). Halyx adds `halyx_version` when it writes a versioned item, and uses
`halyx_expires` for TTL (`aws.dynamodb.ttl-attribute` renames it). Check how existing items' attributes are named and
typed against the entity's components; see [differences.md](differences.md#existing-data).

## Messaging: SNS and SQS

```java
// Before
snsTemplate.convertAndSend("orders", orderJson);                                // or snsClient.publish(...)
sqsTemplate.send(to -> to.queue("orders-billing").payload(orderJson));
@SqsListener("orders-billing") void onOrder(String body) { ... }

// After: halyx.topics.order-events.name: orders     halyx.subscriptions.billing.name: orders-billing
@Qualifier("order-events") Publisher orderEvents;
orderEvents.publish(OutgoingMessage.builder(orderJson).attribute("type", "placed").build());
@HalyxListener("billing") void onOrder(String body) { ... }      // return = delete; throw = redeliver with backoff
```

- A queue fed by an SNS topic: Halyx reads SNS envelopes and raw delivery alike. Raw delivery is recommended.
- Sending straight to a queue (no topic): `halyx.topics.<key>.properties.aws.target: sqs`, with `name` set to the
  queue.
- FIFO: `.fifo` names, `orderingKey` (the message group) and `deduplicationId`.
- Delays: `.delay(...)` on SQS only, not SNS (`DELAYED_DELIVERY`).
- Visibility timeout and acknowledgement mode: Halyx extends leases while a handler runs; `MANUAL` acknowledgement
  becomes a `ReceivedMessage` parameter with `ack()` and `nack(delay)`.
- `listener.concurrency`, `max-backoff` and `batch-size` replace the listener container settings.
- Large payloads stored in S3 (the extended client): `claim-check: { blob-store, threshold }` on both the topic and
  the subscription.

## Events: EventBridge

`eventBridgeClient.putEvents(...)` becomes `EventPublisher.publish(CloudEvent)`, with
`halyx.event-buses.<key>.name: <bus>`. Halyx sends CloudEvents 1.0: the event's `source` and `type` become the
entry's `Source` and `DetailType`, so rules on those still match, but `detail` holds **the whole CloudEvent**, with
the old payload under `data`. Rules that match on payload fields (`detail.status`) must become `detail.data.status`,
and consumers outside Halyx must read `detail.data`. Plan that change with the rule owners. Halyx consumers receive
through the rule's target queue: `halyx.subscriptions.<key>.name: <queue>`, with a `CloudEvent` listener parameter.
They also decode AWS service events, and events from instances still on the old code, with `detail-type` as the type
and `detail` as the data. During the rollout both formats are on the bus: change the rules first, as
[differences.md](differences.md#rollout-old-and-new-instances-side-by-side) describes (tested on Floci: `$or` patterns,
`exists` on `detail.specversion`, and an input transformer that keeps an outside consumer's envelope unchanged).

```java
// Before: EventBridgeClient.putEvents(... .source("acme.loyalty").detailType("TierChanged").detail(json))
tierEvents.publish(CloudEvent.builder("TierChanged", "acme.loyalty").subject(memberId).json(json).build());
```

## Functions: Lambda

```java
// Before: LambdaClient (or AWSLambda in SDK v1)
InvokeResponse r = lambda.invoke(b -> b.functionName("resize-image").qualifier("live")
        .payload(SdkBytes.fromUtf8String(json)));
if (r.functionError() != null) { throw new ResizeFailed(r.payload().asUtf8String()); }
String out = r.payload().asUtf8String();
lambda.invoke(b -> b.functionName("audit").invocationType(InvocationType.EVENT).payload(...));

// After: halyx.functions.resize: { name: resize-image, properties: { aws.lambda.qualifier: live } }
@Qualifier("resize") FunctionInvoker resize;
String out = resize.invoke(FunctionRequest.of(json)).payloadAsString();   // a function error: UnavailableException
audit.trigger(FunctionRequest.of(json));                                 // InvocationType.EVENT: Lambda only
```

- `functionError()` checks go: a function that throws raises `UnavailableException`, with the message
  `Function <name> failed: <errorMessage>` and the `errorType` as `providerErrorCode()`. A service that passed the
  bare `errorMessage` to its clients reads it from the cause: `FunctionError.of(e).map(FunctionError::message)`
  gives the whole `errorMessage`, and `type()` the `errorType`, so its tests keep their assertions. `ResourceNotFoundException` becomes
  `NotFoundException`, throttling `ThrottledException`.
- A `ClientContext` with a `custom` map becomes headers (`FunctionRequest.builder(json).header(k, v)`, or `headers`
  on the function), which arrive in `context.getClientContext().getCustom()` as before; other `ClientContext` fields
  have no equivalent. Lambda takes no `ClientContext` on `EVENT` invocations, so a `trigger` with headers fails.
- `executedVersion()` replaces `InvokeResponse.executedVersion`. `LogType.TAIL` and `DryRun` have no equivalent:
  keep them on `unwrap(LambdaClient.class)` if the service relies on them.
- The SDK client's API call timeout becomes the function's `timeout`. Halyx retries a non-idempotent function only
  where it can't have run (throttling, a failed connect), unlike the SDK's default retries: set `idempotent: true`
  where the old retries were wanted.
- `trigger` is Lambda's alone. For a portable service, publish a message that the function's trigger consumes.

## Keys: KMS

`KmsClient` calls become a `CryptoKey` bean: `halyx.key-stores.kms: { prefix: orders- }` (AWS's store is the
provider's account and region, so no `location`) and `halyx.keys.orders-data: { name: data }`, the key whose alias is
`alias/orders-data`. Portable names are letters, digits and hyphens; keys given as ARNs or key ids need
`native-names: true` on the store.

| Before | After |
|---|---|
| `kms.encrypt(b -> b.keyId(alias).plaintext(...))`, `decrypt` | `key.encrypt(bytes, aad)` and `key.decrypt(SealedData.parse(stored), aad)`: envelope encryption, any size, one call each. **Not the same ciphertext**: see below |
| `generateDataKey` and AES by hand (envelope encryption written in the service) | `key.encrypt(...)`, or `key.generateDataKey()` for a `DataKey` (`plaintext`, `wrapped()`) when the service keeps its own format |
| `reEncrypt` | `key.rewrap(sealed)` (`ReEncrypt` inside KMS) |
| `sign` with `MessageType.RAW` or `DIGEST`, `verify` | `key.sign(data)` / `signDigest(digest)`, and `key.verify(data, signature)`, checked locally with the cached public key. RSA keys name `signing-algorithm` |
| `getPublicKey` | `key.publicKey(version)`, `currentPublicKey()`, `publicKeys()` |
| `generateMac`, `verifyMac` | `key.mac(data)`, `key.verifyMac(data, mac)`: the same HMAC bytes, so stored MACs keep verifying |
| `describeKey` | `key.metadata()` |
| a Nimbus `JWSSigner` over `KmsClient.sign` | `halyx.security.jwt.key: <key bean>`: a `JwtEncoder` and a JWK set endpoint (`docs/spring-boot.md#jwts-signed-by-a-key`) |
| `AwsCrypto` (the AWS Encryption SDK) with a `KmsMasterKeyProvider` | `key.encrypt` / `decrypt`, or `CryptoKeys.encrypt(data, aad, awsKey, otherKey)` for several keys. Its messages aren't HLXS |
| `DynamoDbEncryptionInterceptor` or attribute encryption written by hand | `@Encrypted("<key bean>")` on the record component (`docs/api/nosql.md#encrypted-fields`) |

The encryption context becomes the AAD: the same bytes are needed to decrypt, so derive them from the record (an
order id), as the context was. `KMSInvalidStateException` and `DisabledException` become `AccessDeniedException`,
`NotFoundException` a missing key, `InvalidCiphertextException` `DataIntegrityException`.

**Existing data:** direct `Encrypt` ciphertext, AWS Encryption SDK messages, AWS Database Encryption SDK items and
S3 Encryption Client objects are not HLXS sealed data, and Halyx doesn't open them. Plan it per store
([differences.md](differences.md#existing-data)): keep a small `unwrap(KmsClient.class)` class that decrypts the old
format, and reseal values as they are read or in a batch, or keep the store on the old code. Signatures and MACs
carry over: `new Signature(bytes, algorithm, Optional.empty())` wraps a stored raw signature (DER for ECDSA, as KMS
returns it), and `verifyMac` takes the stored MAC as it is.

## Streams: MSK

- Spring Kafka with `aws-msk-iam-auth` (`sasl.jaas.config: ...IAMLoginModule required;`): drop the SASL settings and
  `spring.kafka.bootstrap-servers`, define `halyx.stream-clusters.events: { bootstrap-servers, auth: { type: iam } }`,
  and set `halyx.spring-kafka.cluster: events`. `KafkaTemplate` and `@KafkaListener` stay as they are, and so do
  the serializers, the group ids and the record format other teams read. Drop `aws-msk-iam-auth`. Put both in the
  cloud profile: a `local` profile keeps `spring.kafka.bootstrap-servers` (see [testing.md](testing.md)).
- Or move to `StreamProducer` and `@HalyxListener` on `halyx.stream-consumers` for retries and dead letters in Halyx.

## Caches: ElastiCache

`spring.data.redis.*` with an ElastiCache endpoint: for key-value use, a `halyx.caches.<key>` bean with
`auth: { type: iam, username }` and `properties: { aws.elasticache.cache-name: <replication group> }`. For
`@Cacheable`, `halyx.cache-manager` with the same settings (a YAML anchor shares them). Hand-written IAM code goes:
a `LettuceClientConfigurationBuilderCustomizer` or `RedisCredentialsProviderFactory` presigning `Action=connect`
with SigV4. `StringRedisTemplate` counters (`INCR`, then `EXPIRE` on the first) become
`increment(key, 1, ttl)` on a `Cache<String>` with `namespace: ""`, which keeps the keys. `RedisTemplate` features beyond key-value, TTLs,
counters and locks (streams, pub/sub, sorted sets, Lua) have no Halyx equivalent: keep Spring Data Redis for them.

## SQL: RDS and Aurora

`jdbc:aws-wrapper:postgresql://...?wrapperPlugins=iam`, or a hand-built token provider: `halyx.databases.orders-db:
{ engine: postgresql, host, database, auth: { type: iam, username }, pool: { max-size, min-idle } }`. Remove
`spring.datasource.*`, including `driver-class-name: software.amazon.jdbc.Driver` and `hikari.*` (the pool settings
move to `pool`); Boot's `DataSourceAutoConfiguration` backs off. JPA, `JdbcTemplate` and Flyway use the Halyx pool,
and the Flyway history carries over. Passwords from Secrets Manager:
`auth: { type: password, username, secret: { store: secrets, name: orders/db, field: password } }`. The AWS Advanced
JDBC Wrapper's failover plugins have no Halyx equivalent yet: point `host` at the cluster endpoint.

## No Halyx equivalent

Kinesis Data Streams, Step Functions, Lambda management (creating and updating functions), SES, Cognito, KMS key
management (creating and rotating keys, key policies, grants), KMS `GenerateRandom` and asymmetric `Encrypt`, S3
Select, S3 event notifications configuration, DynamoDB Streams, scans and PartiQL. Keep these on the
SDK, behind one class per feature, and list them in the report.
