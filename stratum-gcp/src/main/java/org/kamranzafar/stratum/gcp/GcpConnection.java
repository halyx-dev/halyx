package org.kamranzafar.stratum.gcp;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.cache.CacheAuth;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.cache.spi.CacheDriver;
import org.kamranzafar.stratum.gcp.memorystore.MemorystoreIamTokens;
import org.kamranzafar.stratum.gcp.storage.GcsBlobStoreDriver;
import org.kamranzafar.stratum.redis.RedisCaches;
import org.kamranzafar.stratum.spi.ProviderConnection;
import org.kamranzafar.stratum.storage.spi.BlobStoreDriver;

/** Owns the Google Cloud clients for one configuration. Clients are created lazily and shared by all buckets. */
final class GcpConnection implements ProviderConnection {

    private final ProviderConfig config;
    private Storage storage;
    private RedisCaches caches;

    GcpConnection(ProviderConfig config) {
        this.config = config;
    }

    @Override
    public synchronized BlobStoreDriver blobStore(String bucket) {
        if (storage == null) {
            var builder = StorageOptions.http();
            config.property(GcpProperties.PROJECT_ID).ifPresent(builder::setProjectId);
            config.endpoint().ifPresent(endpoint -> builder.setHost(endpoint.toString()));
            switch (config.credentials()) {
                case Credentials.DefaultChain ignored -> { } // Application Default Credentials
                case Credentials.Anonymous ignored -> builder.setCredentials(NoCredentials.getInstance());
                case Credentials.Static ignored -> throw new IllegalArgumentException(
                        "GCP does not use static key pairs; use Credentials.defaultChain() with "
                                + "GOOGLE_APPLICATION_CREDENTIALS or workload identity");
            }
            storage = builder.build().getService();
        }
        return new GcsBlobStoreDriver(bucket, storage, config.endpoint().isPresent());
    }

    @Override
    public synchronized CacheDriver cache(CacheConfig cacheConfig) {
        if (caches == null) {
            caches = new RedisCaches(GcpProvider.ID);
        }
        return caches.driver(cacheConfig, RedisCaches.credentials(cacheConfig.auth(), this::iamTokens));
    }

    private MemorystoreIamTokens iamTokens(CacheAuth.Iam iam) {
        if (!(config.credentials() instanceof Credentials.DefaultChain)) {
            throw new IllegalArgumentException("Memorystore IAM authentication uses Application Default Credentials; "
                    + "configure the provider with Credentials.defaultChain()");
        }
        return MemorystoreIamTokens.applicationDefault(iam.username());
    }

    @Override
    public synchronized void close() {
        if (caches != null) {
            caches.close();
            caches = null;
        }
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close GCS client", e);
            } finally {
                storage = null;
            }
        }
    }
}
