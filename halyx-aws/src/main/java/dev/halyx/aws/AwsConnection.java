/*
 * Copyright 2026 Kamran Zafar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.halyx.aws;

import dev.halyx.aws.dynamodb.DynamoDocumentStoreDriver;
import dev.halyx.aws.elasticache.ElastiCacheIamTokens;
import dev.halyx.aws.messaging.SnsPublisherDriver;
import dev.halyx.aws.events.EventBridgePublisherDriver;
import dev.halyx.aws.msk.MskIamTokens;
import dev.halyx.aws.messaging.SqsPublisherDriver;
import dev.halyx.aws.messaging.SqsSubscriptionDriver;
import dev.halyx.aws.parameters.SecretsManagerDriver;
import dev.halyx.aws.parameters.SsmParameterDriver;
import dev.halyx.aws.rds.RdsIamTokens;
import dev.halyx.aws.s3.S3BlobStoreDriver;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.parameters.spi.ParameterDriver;
import dev.halyx.cache.CacheAuth;
import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.config.Credentials;
import dev.halyx.config.HttpSettings;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.jdbc.JdbcDatabases;
import dev.halyx.events.EventBusConfig;
import dev.halyx.events.spi.EventPublisherDriver;
import dev.halyx.kafka.KafkaCredentialsSource;
import dev.halyx.kafka.KafkaStreamClients;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.spi.DocumentStoreDriver;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.messaging.spi.PublisherDriver;
import dev.halyx.messaging.spi.SubscriptionDriver;
import dev.halyx.redis.RedisCaches;
import dev.halyx.spi.ProviderConnection;
import dev.halyx.sql.Database;
import dev.halyx.sql.SqlAuth;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEndpoint;
import dev.halyx.sql.SqlEngine;
import dev.halyx.storage.spi.BlobStoreDriver;
import dev.halyx.capability.Capabilities;
import dev.halyx.stream.StreamCapability;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.ProducerDriver;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.services.eventbridge.EventBridgeAsyncClient;
import software.amazon.awssdk.services.sns.SnsAsyncClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.utils.SdkAutoCloseable;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.http.async.SdkAsyncHttpClient;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerAsyncClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmAsyncClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.util.Locale;
import java.util.Map;

/**
 * Owns the AWS SDK clients for one configuration. Clients are created lazily and shared by all buckets,
 * and all of them send requests through one pooled HTTP client tuned by {@link HttpSettings}.
 */
final class AwsConnection implements ProviderConnection {

    private final ProviderConfig config;
    private SdkHttpClient http;
    private JdbcDatabases databases;
    private SnsClient sns;
    private DynamoDbClient dynamo;
    private SqsClient sqs;
    private EventBridgeClient eventBridge;
    private S3Client s3;
    private S3Presigner presigner;
    private S3AsyncClient s3Async;
    private DynamoDbAsyncClient dynamoAsync;
    private SnsAsyncClient snsAsync;
    private SqsAsyncClient sqsAsync;
    private EventBridgeAsyncClient eventBridgeAsync;
    private SdkAsyncHttpClient nettyHttp;
    private RedisCaches caches;
    private KafkaStreamClients streams;
    private SsmClient ssm;
    private SecretsManagerClient secretsManager;
    private SsmAsyncClient ssmAsync;
    private SecretsManagerAsyncClient secretsManagerAsync;
    private AwsCredentialsProvider credentials;
    private StsClient sts;

    AwsConnection(ProviderConfig config) {
        this.config = config;
    }

    @Override
    public synchronized BlobStoreDriver blobStore(String bucket) {
        if (s3 == null) {
            Region region = region();
            AwsCredentialsProvider credentials = credentials();
            boolean pathStyle = config.booleanProperty(AwsProperties.S3_PATH_STYLE, false);

            var client = S3Client.builder().region(region).credentialsProvider(credentials).forcePathStyle(pathStyle)
                    .httpClient(http()).overrideConfiguration(overrides());
            var signer = S3Presigner.builder().region(region).credentialsProvider(credentials)
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
            config.endpoint().ifPresent(endpoint -> {
                client.endpointOverride(endpoint);
                signer.endpointOverride(endpoint);
            });
            s3 = client.build();
            presigner = signer.build();
        }
        long threshold = config.property(AwsProperties.S3_MULTIPART_THRESHOLD).map(Long::parseLong)
                .orElse(AwsProperties.DEFAULT_MULTIPART_THRESHOLD);
        return new S3BlobStoreDriver(bucket, s3, presigner, threshold, this::s3Async);
    }

    /** The non-blocking S3 client, made on the first asynchronous call, on its own Netty connection pool. */
    private synchronized S3AsyncClient s3Async() {
        if (s3Async == null) {
            var client = S3AsyncClient.builder().region(region()).credentialsProvider(credentials())
                    .forcePathStyle(config.booleanProperty(AwsProperties.S3_PATH_STYLE, false))
                    .httpClient(nettyHttp())
                    .overrideConfiguration(overrides());
            config.endpoint().ifPresent(client::endpointOverride);
            s3Async = client.build();
        }
        return s3Async;
    }

    /** The non-blocking DynamoDB client, made on the first asynchronous call. */
    private synchronized DynamoDbAsyncClient dynamoAsync() {
        if (dynamoAsync == null) {
            var builder = DynamoDbAsyncClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(nettyHttp()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            dynamoAsync = builder.build();
        }
        return dynamoAsync;
    }

    /** The non-blocking SNS client, made on the first asynchronous publish. */
    private synchronized SnsAsyncClient snsAsync() {
        if (snsAsync == null) {
            var builder = SnsAsyncClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(nettyHttp()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            snsAsync = builder.build();
        }
        return snsAsync;
    }

    /** The non-blocking SQS client, made on the first asynchronous send. */
    private synchronized SqsAsyncClient sqsAsync() {
        if (sqsAsync == null) {
            var builder = SqsAsyncClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(nettyHttp()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            sqsAsync = builder.build();
        }
        return sqsAsync;
    }

    /** The non-blocking EventBridge client, made on the first asynchronous publish. */
    private synchronized EventBridgeAsyncClient eventBridgeAsync() {
        if (eventBridgeAsync == null) {
            var builder = EventBridgeAsyncClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(nettyHttp()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            eventBridgeAsync = builder.build();
        }
        return eventBridgeAsync;
    }

    /** The non-blocking SSM client, made on the first asynchronous call. */
    private synchronized SsmAsyncClient ssmAsync() {
        if (ssmAsync == null) {
            var builder = SsmAsyncClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(nettyHttp()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            ssmAsync = builder.build();
        }
        return ssmAsync;
    }

    /** The non-blocking Secrets Manager client, made on the first asynchronous call. */
    private synchronized SecretsManagerAsyncClient secretsManagerAsync() {
        if (secretsManagerAsync == null) {
            var builder = SecretsManagerAsyncClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(nettyHttp()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            secretsManagerAsync = builder.build();
        }
        return secretsManagerAsync;
    }

    /** One Netty connection pool for every non-blocking client, tuned by {@link HttpSettings} as the blocking one is. */
    private synchronized SdkAsyncHttpClient nettyHttp() {
        if (nettyHttp == null) {
            HttpSettings settings = HttpSettings.from(config);
            nettyHttp = NettyNioAsyncHttpClient.builder()
                    .maxConcurrency(settings.maxConnections())
                    .connectionTimeout(settings.connectTimeout())
                    .readTimeout(settings.readTimeout())
                    .connectionMaxIdleTime(settings.idleTimeout())
                    .connectionAcquisitionTimeout(settings.acquireTimeout())
                    .build();
        }
        return nettyHttp;
    }

    @Override
    public synchronized CacheDriver cache(CacheConfig cacheConfig) {
        if (caches == null) {
            caches = new RedisCaches(AwsProvider.ID);
        }
        return caches.driver(cacheConfig, RedisCaches.credentials(cacheConfig.auth(), iam -> iamTokens(cacheConfig, iam)));
    }

    @Override
    public synchronized ParameterDriver parameterStore(ParameterStoreConfig storeConfig) {
        return switch (storeConfig.kind()) {
            case PARAMETERS -> {
                if (ssm == null) {
                    var builder = SsmClient.builder().region(region()).credentialsProvider(credentials())
                            .httpClient(http()).overrideConfiguration(overrides());
                    config.endpoint().ifPresent(builder::endpointOverride);
                    ssm = builder.build();
                }
                yield new SsmParameterDriver(ssm,
                        Boolean.parseBoolean(storeConfig.property(SsmParameterDriver.SECURE).orElse("false")),
                        storeConfig.nativeNames(), this::ssmAsync);
            }
            case SECRETS -> {
                if (secretsManager == null) {
                    var builder = SecretsManagerClient.builder().region(region()).credentialsProvider(credentials())
                            .httpClient(http()).overrideConfiguration(overrides());
                    config.endpoint().ifPresent(builder::endpointOverride);
                    secretsManager = builder.build();
                }
                yield new SecretsManagerDriver(secretsManager, storeConfig.nativeNames(), this::secretsManagerAsync);
            }
        };
    }

    private ElastiCacheIamTokens iamTokens(CacheConfig cacheConfig, CacheAuth.Iam iam) {
        String userId = iam.username().orElseThrow(() -> new IllegalArgumentException(
                "ElastiCache IAM auth needs the ElastiCache user id: CacheAuth.iam(\"<user-id>\")"));
        String cacheName = cacheConfig.property(AwsProperties.ELASTICACHE_CACHE_NAME).orElseThrow(() ->
                new IllegalArgumentException("ElastiCache IAM auth needs the '" + AwsProperties.ELASTICACHE_CACHE_NAME
                        + "' cache property (replication group id or serverless cache name)"));
        boolean serverless = Boolean.parseBoolean(cacheConfig.property(AwsProperties.ELASTICACHE_SERVERLESS).orElse("false"));
        return new ElastiCacheIamTokens(cacheName, userId, region().id(), serverless, credentials());
    }

    private Region region() {
        return config.region().map(Region::of).orElseGet(() -> DefaultAwsRegionProviderChain.builder().build().getRegion());
    }

    /** Made once and shared by every client, so the default chain resolves and refreshes credentials once. */
    private synchronized AwsCredentialsProvider credentials() {
        if (credentials == null) {
            credentials = credentials(config.credentials());
        }
        return credentials;
    }

    private AwsCredentialsProvider credentials(Credentials source) {
        return switch (source) {
            case Credentials.DefaultChain ignored -> DefaultCredentialsProvider.builder().build();
            case Credentials.Static s -> StaticCredentialsProvider.create(AwsBasicCredentials.create(s.id(), s.secret()));
            case Credentials.Anonymous ignored -> AnonymousCredentialsProvider.create();
            case Credentials.Impersonation role -> assumeRole(role);
            case Credentials.ServicePrincipal ignored -> throw new IllegalArgumentException("A Microsoft Entra ID service "
                    + "principal logs in to Azure, not AWS: use the default chain, an access key or an assumed role");
        };
    }

    /**
     * An IAM role through STS, starting from the source credentials. The provider refreshes the role's temporary
     * credentials before they expire.
     */
    private AwsCredentialsProvider assumeRole(Credentials.Impersonation role) {
        if (!role.target().startsWith("arn:")) {
            throw new IllegalArgumentException("On AWS, impersonation assumes an IAM role: pass its ARN, such as "
                    + "arn:aws:iam::123456789012:role/orders, not '" + role.target() + "'");
        }
        var client = StsClient.builder().region(region()).credentialsProvider(credentials(role.source()))
                .httpClient(http()).overrideConfiguration(overrides());
        config.endpoint().ifPresent(client::endpointOverride);
        sts = client.build();
        var request = AssumeRoleRequest.builder().roleArn(role.target())
                .roleSessionName(role.sessionName() != null ? role.sessionName() : "halyx");
        if (role.externalId() != null) {
            request.externalId(role.externalId());
        }
        if (role.lifetime() != null) {
            request.durationSeconds(Math.toIntExact(role.lifetime().toSeconds()));
        }
        return StsAssumeRoleCredentialsProvider.builder().stsClient(sts).refreshRequest(request.build()).build();
    }

    /** A DynamoDB table. */
    @Override
    public synchronized DocumentStoreDriver documentStore(TableConfig table) {
        if (dynamo == null) {
            var builder = DynamoDbClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(http()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            dynamo = builder.build();
        }
        return new DynamoDocumentStoreDriver(dynamo, table, this::dynamoAsync);
    }

    /** An SNS topic, or with {@code aws.target=sqs} an SQS queue. */
    @Override
    public synchronized PublisherDriver publisher(TopicConfig topic) {
        String target = topic.property(AwsProperties.MESSAGING_TARGET).orElse("sns");
        return switch (target) {
            case "sns" -> new SnsPublisherDriver(sns(), topic.name(), this::snsAsync);
            case "sqs" -> new SqsPublisherDriver(sqs(), topic.name(), this::sqsAsync);
            default -> throw new IllegalArgumentException(AwsProperties.MESSAGING_TARGET + " must be sns or sqs, not " + target);
        };
    }

    /** An SQS queue, on its own or subscribed to an SNS topic. */
    @Override
    public synchronized SubscriptionDriver subscription(SubscriptionConfig subscription) {
        return new SqsSubscriptionDriver(sqs(), subscription.name(), this::sqsAsync);
    }

    private SnsClient sns() {
        if (sns == null) {
            var builder = SnsClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(http()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            sns = builder.build();
        }
        return sns;
    }

    @Override
    public synchronized EventPublisherDriver eventPublisher(EventBusConfig bus) {
        return new EventBridgePublisherDriver(eventBridge(), bus.name(), this::eventBridgeAsync);
    }

    private EventBridgeClient eventBridge() {
        if (eventBridge == null) {
            var builder = EventBridgeClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(http()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            eventBridge = builder.build();
        }
        return eventBridge;
    }

    private SqsClient sqs() {
        if (sqs == null) {
            var builder = SqsClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(http()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            sqs = builder.build();
        }
        return sqs;
    }

    /** An MSK topic: Apache Kafka, so idempotent and transactional producers are available. */
    private static final Capabilities MSK_CAPABILITIES = Capabilities.of(StreamCapability.values());

    @Override
    public synchronized ProducerDriver streamProducer(StreamProducerConfig producer) {
        return streams().producer(producer, mskCredentials(producer.cluster()), MSK_CAPABILITIES, Map.of());
    }

    @Override
    public synchronized ConsumerDriver streamConsumer(StreamConsumerConfig consumer) {
        return streams().consumer(consumer, mskCredentials(consumer.cluster()), MSK_CAPABILITIES, Map.of());
    }

    @Override
    public Map<String, Object> kafkaClientProperties(StreamConfig cluster) {
        return KafkaStreamClients.clientProperties(cluster, mskCredentials(cluster), Map.of());
    }

    private KafkaStreamClients streams() {
        if (streams == null) {
            streams = new KafkaStreamClients(AwsProvider.ID);
        }
        return streams;
    }

    /** MSK IAM tokens for the cluster's region, signed with the connection's credentials. */
    private KafkaCredentialsSource mskCredentials(StreamConfig cluster) {
        return KafkaStreamClients.credentials(cluster.auth(), () -> new MskIamTokens(
                cluster.property(AwsProperties.MSK_REGION).orElseGet(() -> region().id()), credentials()));
    }

    /** Password auth, or RDS IAM tokens for PostgreSQL and MySQL (RDS for SQL Server has no IAM login). */
    @Override
    public synchronized Database database(SqlConfig sql) {
        if (databases == null) {
            databases = new JdbcDatabases();
        }
        return switch (sql.auth()) {
            case SqlAuth.Password ignored -> databases.createWithPassword(sql);
            case SqlAuth.Iam iam -> databases.create(sql, sql.jdbcUrl(), rdsIamTokens(sql, iam),
                    JdbcDatabases.requireTls(sql.engine()));
        };
    }

    @Override
    public SqlEndpoint sqlEndpoint(SqlConfig sql) {
        String host = sql.host().orElseThrow(() -> new IllegalArgumentException("RDS needs a host"));
        return switch (sql.auth()) {
            case SqlAuth.Password password -> new SqlEndpoint(sql.engine(), host, sql.port(), sql.database(),
                    JdbcDatabases.passwordSource(password), false);
            case SqlAuth.Iam iam -> new SqlEndpoint(sql.engine(), host, sql.port(), sql.database(),
                    rdsIamTokens(sql, iam), true);
        };
    }

    /** RDS IAM tokens for the database user, signed for the instance's region. */
    private RdsIamTokens rdsIamTokens(SqlConfig sql, SqlAuth.Iam iam) {
        if (sql.engine() == SqlEngine.SQLSERVER) {
            throw new UnsupportedFeatureException("RDS IAM authentication supports PostgreSQL and MySQL, "
                    + "not SQL Server; use password auth", null, AwsProvider.ID, null);
        }
        String username = iam.username().orElseThrow(() -> new IllegalArgumentException(
                "RDS IAM authentication needs the database user: SqlAuth.iam(\"<user>\")"));
        String host = sql.host().orElseThrow(() -> new IllegalArgumentException("RDS needs a host"));
        String region = sql.property(AwsProperties.RDS_REGION).orElseGet(() -> region().id());
        return new RdsIamTokens(host, sql.port(), username, region, credentials());
    }

    private ClientOverrideConfiguration overrides() {
        String mode = config.property(AwsProperties.RETRY_MODE).orElse("adaptive").trim().toLowerCase(Locale.ROOT);
        RetryMode retryMode = switch (mode) {
            case "adaptive" -> RetryMode.ADAPTIVE_V2;
            case "standard" -> RetryMode.STANDARD;
            case "legacy" -> RetryMode.LEGACY;
            default -> throw new IllegalArgumentException(AwsProperties.RETRY_MODE
                    + " must be adaptive, standard or legacy, not '" + mode + "'");
        };
        return ClientOverrideConfiguration.builder().retryStrategy(retryMode).build();
    }

    private SdkHttpClient http() {
        if (http == null) {
            HttpSettings settings = HttpSettings.from(config);
            http = Apache5HttpClient.builder()
                    .maxConnections(settings.maxConnections())
                    .connectionTimeout(settings.connectTimeout())
                    .socketTimeout(settings.readTimeout())
                    .connectionMaxIdleTime(settings.idleTimeout())
                    .connectionAcquisitionTimeout(settings.acquireTimeout())
                    .tcpKeepAlive(true)
                    .build();
        }
        return http;
    }

    @Override
    public synchronized void close() {
        if (streams != null) {
            streams.close();
            streams = null;
        }
        if (databases != null) {
            databases.close();
            databases = null;
        }
        if (sns != null) {
            sns.close();
            sns = null;
        }
        if (dynamo != null) {
            dynamo.close();
            dynamo = null;
        }
        if (sqs != null) {
            sqs.close();
            sqs = null;
        }
        if (eventBridge != null) {
            eventBridge.close();
            eventBridge = null;
        }
        if (caches != null) {
            caches.close();
            caches = null;
        }
        if (ssm != null) {
            ssm.close();
            ssm = null;
        }
        if (secretsManager != null) {
            secretsManager.close();
            secretsManager = null;
        }
        if (ssmAsync != null) {
            ssmAsync.close();
            ssmAsync = null;
        }
        if (secretsManagerAsync != null) {
            secretsManagerAsync.close();
            secretsManagerAsync = null;
        }
        if (s3Async != null) {
            s3Async.close();
            s3Async = null;
        }
        if (dynamoAsync != null) {
            dynamoAsync.close();
            dynamoAsync = null;
        }
        if (snsAsync != null) {
            snsAsync.close();
            snsAsync = null;
        }
        if (sqsAsync != null) {
            sqsAsync.close();
            sqsAsync = null;
        }
        if (eventBridgeAsync != null) {
            eventBridgeAsync.close();
            eventBridgeAsync = null;
        }
        if (nettyHttp != null) {
            nettyHttp.close(); // clients never close an HTTP client they were given
            nettyHttp = null;
        }
        if (s3 != null) {
            s3.close();
            presigner.close();
            s3 = null;
            presigner = null;
        }
        if (http != null) {
            // Clients never close an HTTP client they were given.
            http.close();
            http = null;
        }
        // Nor the credentials provider they were given.
        if (credentials instanceof SdkAutoCloseable closeable) {
            closeable.close();
        }
        credentials = null;
        if (sts != null) {
            sts.close();
            sts = null;
        }
    }
}
