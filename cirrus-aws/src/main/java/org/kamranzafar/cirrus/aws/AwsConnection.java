package org.kamranzafar.cirrus.aws;

import org.kamranzafar.cirrus.aws.elasticache.ElastiCacheIamTokens;
import org.kamranzafar.cirrus.aws.parameters.SecretsManagerDriver;
import org.kamranzafar.cirrus.aws.parameters.SsmParameterDriver;
import org.kamranzafar.cirrus.aws.rds.RdsIamTokens;
import org.kamranzafar.cirrus.aws.s3.S3BlobStoreDriver;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.parameters.spi.ParameterDriver;
import org.kamranzafar.cirrus.cache.CacheAuth;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.cache.spi.CacheDriver;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.HttpSettings;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;
import org.kamranzafar.cirrus.jdbc.JdbcDatabases;
import org.kamranzafar.cirrus.redis.RedisCaches;
import org.kamranzafar.cirrus.spi.ProviderConnection;
import org.kamranzafar.cirrus.sql.Database;
import org.kamranzafar.cirrus.sql.SqlAuth;
import org.kamranzafar.cirrus.sql.SqlConfig;
import org.kamranzafar.cirrus.sql.SqlEngine;
import org.kamranzafar.cirrus.storage.spi.BlobStoreDriver;
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
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
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
