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
package org.kamranzafar.halyx.aws;

import org.kamranzafar.halyx.aws.dynamodb.DynamoDocumentStoreDriver;
import org.kamranzafar.halyx.aws.elasticache.ElastiCacheIamTokens;
import org.kamranzafar.halyx.aws.messaging.SnsPublisherDriver;
import org.kamranzafar.halyx.aws.messaging.SqsPublisherDriver;
import org.kamranzafar.halyx.aws.messaging.SqsSubscriptionDriver;
import org.kamranzafar.halyx.aws.parameters.SecretsManagerDriver;
import org.kamranzafar.halyx.aws.parameters.SsmParameterDriver;
import org.kamranzafar.halyx.aws.rds.RdsIamTokens;
import org.kamranzafar.halyx.aws.s3.S3BlobStoreDriver;
import org.kamranzafar.halyx.parameters.ParameterStoreConfig;
import org.kamranzafar.halyx.parameters.spi.ParameterDriver;
import org.kamranzafar.halyx.cache.CacheAuth;
import org.kamranzafar.halyx.cache.CacheConfig;
import org.kamranzafar.halyx.cache.spi.CacheDriver;
import org.kamranzafar.halyx.config.Credentials;
import org.kamranzafar.halyx.config.HttpSettings;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.error.UnsupportedFeatureException;
import org.kamranzafar.halyx.jdbc.JdbcDatabases;
import org.kamranzafar.halyx.messaging.SubscriptionConfig;
import org.kamranzafar.halyx.nosql.TableConfig;
import org.kamranzafar.halyx.nosql.spi.DocumentStoreDriver;
import org.kamranzafar.halyx.messaging.TopicConfig;
import org.kamranzafar.halyx.messaging.spi.PublisherDriver;
import org.kamranzafar.halyx.messaging.spi.SubscriptionDriver;
import org.kamranzafar.halyx.redis.RedisCaches;
import org.kamranzafar.halyx.spi.ProviderConnection;
import org.kamranzafar.halyx.sql.Database;
import org.kamranzafar.halyx.sql.SqlAuth;
import org.kamranzafar.halyx.sql.SqlConfig;
import org.kamranzafar.halyx.sql.SqlEngine;
import org.kamranzafar.halyx.storage.spi.BlobStoreDriver;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.Locale;

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
    private S3Client s3;
    private S3Presigner presigner;
    private RedisCaches caches;
    private SsmClient ssm;
    private SecretsManagerClient secretsManager;

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
        return new S3BlobStoreDriver(bucket, s3, presigner, threshold);
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
                        storeConfig.nativeNames());
            }
            case SECRETS -> {
                if (secretsManager == null) {
                    var builder = SecretsManagerClient.builder().region(region()).credentialsProvider(credentials())
                            .httpClient(http()).overrideConfiguration(overrides());
                    config.endpoint().ifPresent(builder::endpointOverride);
                    secretsManager = builder.build();
                }
                yield new SecretsManagerDriver(secretsManager, storeConfig.nativeNames());
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

    private AwsCredentialsProvider credentials() {
        return switch (config.credentials()) {
            case Credentials.DefaultChain ignored -> DefaultCredentialsProvider.builder().build();
            case Credentials.Static s -> StaticCredentialsProvider.create(AwsBasicCredentials.create(s.id(), s.secret()));
            case Credentials.Anonymous ignored -> AnonymousCredentialsProvider.create();
        };
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
        return new DynamoDocumentStoreDriver(dynamo, table);
    }

    /** An SNS topic, or with {@code aws.target=sqs} an SQS queue. */
    @Override
    public synchronized PublisherDriver publisher(TopicConfig topic) {
        String target = topic.property(AwsProperties.MESSAGING_TARGET).orElse("sns");
        return switch (target) {
            case "sns" -> new SnsPublisherDriver(sns(), topic.name());
            case "sqs" -> new SqsPublisherDriver(sqs(), topic.name());
            default -> throw new IllegalArgumentException(AwsProperties.MESSAGING_TARGET + " must be sns or sqs, not " + target);
        };
    }

    /** An SQS queue, on its own or subscribed to an SNS topic. */
    @Override
    public synchronized SubscriptionDriver subscription(SubscriptionConfig subscription) {
        return new SqsSubscriptionDriver(sqs(), subscription.name());
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

    private SqsClient sqs() {
        if (sqs == null) {
            var builder = SqsClient.builder().region(region()).credentialsProvider(credentials())
                    .httpClient(http()).overrideConfiguration(overrides());
            config.endpoint().ifPresent(builder::endpointOverride);
            sqs = builder.build();
        }
        return sqs;
    }

    /** Password auth, or RDS IAM tokens for PostgreSQL and MySQL (RDS for SQL Server has no IAM login). */
    @Override
    public synchronized Database database(SqlConfig sql) {
        if (databases == null) {
            databases = new JdbcDatabases();
        }
        return switch (sql.auth()) {
            case SqlAuth.Password ignored -> databases.createWithPassword(sql);
            case SqlAuth.Iam iam -> {
                if (sql.engine() == SqlEngine.SQLSERVER) {
                    throw new UnsupportedFeatureException("RDS IAM authentication supports PostgreSQL and MySQL, "
                            + "not SQL Server; use password auth", null, AwsProvider.ID, null);
                }
                String username = iam.username().orElseThrow(() -> new IllegalArgumentException(
                        "RDS IAM authentication needs the database user: SqlAuth.iam(\"<user>\")"));
                String host = sql.host().orElseThrow(() -> new IllegalArgumentException("RDS needs a host"));
                String region = sql.property(AwsProperties.RDS_REGION).orElseGet(() -> region().id());
                yield databases.create(sql, sql.jdbcUrl(),
                        new RdsIamTokens(host, sql.port(), username, region, credentials()),
                        JdbcDatabases.requireTls(sql.engine()));
            }
        };
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
    }
}
