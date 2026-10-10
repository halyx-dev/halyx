package org.kamranzafar.stratum.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import org.kamranzafar.stratum.azure.blob.AzureBlobStoreDriver;
import org.kamranzafar.stratum.azure.redis.EntraIdRedisTokens;
import org.kamranzafar.stratum.cache.CacheAuth;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.cache.spi.CacheDriver;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.redis.RedisCaches;
import org.kamranzafar.stratum.spi.ProviderConnection;
import org.kamranzafar.stratum.storage.spi.BlobStoreDriver;

import java.util.Optional;

/** Owns the Azure SDK clients for one configuration. Clients are created lazily and shared by all containers. */
final class AzureConnection implements ProviderConnection {

    private final ProviderConfig config;
    private BlobServiceClient blobService;
    private RedisCaches caches;

    AzureConnection(ProviderConfig config) {
        this.config = config;
    }

    @Override
    public synchronized BlobStoreDriver blobStore(String container) {
        if (blobService == null) {
            var builder = new BlobServiceClientBuilder().endpoint(blobEndpoint());
            switch (config.credentials()) {
                case Credentials.DefaultChain ignored -> builder.credential(new DefaultAzureCredentialBuilder().build());
                case Credentials.Static s -> builder.credential(new StorageSharedKeyCredential(s.id(), s.secret()));
                case Credentials.Anonymous ignored -> { }
            }
            blobService = builder.buildClient();
        }
        boolean sharedKey = config.credentials() instanceof Credentials.Static;
        return new AzureBlobStoreDriver(container, blobService, sharedKey);
    }

    @Override
    public synchronized CacheDriver cache(CacheConfig cacheConfig) {
        if (caches == null) {
            caches = new RedisCaches(AzureProvider.ID);
        }
        return caches.driver(cacheConfig, RedisCaches.credentials(cacheConfig.auth(), this::entraTokens));
    }

    private EntraIdRedisTokens entraTokens(CacheAuth.Iam iam) {
        if (!(config.credentials() instanceof Credentials.DefaultChain)) {
            throw new IllegalArgumentException("Entra ID cache authentication uses the default Azure credential chain; "
                    + "configure the provider with Credentials.defaultChain()");
        }
        return new EntraIdRedisTokens(new DefaultAzureCredentialBuilder().build(), iam.username());
    }

    private String blobEndpoint() {
        return config.endpoint().map(Object::toString).orElseGet(() -> {
            String account = config.property(AzureProperties.STORAGE_ACCOUNT)
                    .or(() -> config.credentials() instanceof Credentials.Static s
                            ? Optional.of(s.id()) : Optional.empty())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Azure needs an endpoint or the '" + AzureProperties.STORAGE_ACCOUNT + "' property"));
            return "https://" + account + ".blob.core.windows.net";
        });
    }

    @Override
    public synchronized void close() {
        // The sync Azure clients hold no resources that need explicit release.
        blobService = null;
        if (caches != null) {
            caches.close();
            caches = null;
        }
    }
}
