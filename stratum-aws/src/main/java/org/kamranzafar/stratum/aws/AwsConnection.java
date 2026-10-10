package org.kamranzafar.stratum.aws;

import org.kamranzafar.stratum.aws.elasticache.ElastiCacheIamTokens;
import org.kamranzafar.stratum.aws.parameters.SecretsManagerDriver;
import org.kamranzafar.stratum.aws.parameters.SsmParameterDriver;
import org.kamranzafar.stratum.aws.s3.S3BlobStoreDriver;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.kamranzafar.stratum.parameters.spi.ParameterDriver;
import org.kamranzafar.stratum.cache.CacheAuth;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.cache.spi.CacheDriver;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.redis.RedisCaches;
import org.kamranzafar.stratum.spi.ProviderConnection;
import org.kamranzafar.stratum.storage.spi.BlobStoreDriver;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Owns the AWS SDK clients for one configuration. Clients are created lazily and shared by all buckets. */
final class AwsConnection implements ProviderConnection {

    private final ProviderConfig config;
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

            var client = S3Client.builder().region(region).credentialsProvider(credentials).forcePathStyle(pathStyle);
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
                    var builder = SsmClient.builder().region(region()).credentialsProvider(credentials());
                    config.endpoint().ifPresent(builder::endpointOverride);
                    ssm = builder.build();
                }
                yield new SsmParameterDriver(ssm,
                        Boolean.parseBoolean(storeConfig.property(SsmParameterDriver.SECURE).orElse("false")),
                        storeConfig.nativeNames());
            }
            case SECRETS -> {
                if (secretsManager == null) {
                    var builder = SecretsManagerClient.builder().region(region()).credentialsProvider(credentials());
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

    @Override
    public synchronized void close() {
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
    }
}
