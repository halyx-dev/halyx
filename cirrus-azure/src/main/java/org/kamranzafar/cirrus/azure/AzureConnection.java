package org.kamranzafar.cirrus.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.util.HttpClientOptions;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import org.kamranzafar.cirrus.azure.blob.AzureBlobStoreDriver;
import org.kamranzafar.cirrus.azure.parameters.AppConfigurationDriver;
import org.kamranzafar.cirrus.azure.parameters.AzureParameterProperties;
import org.kamranzafar.cirrus.azure.parameters.ForceHttpPolicy;
import org.kamranzafar.cirrus.azure.parameters.KeyVaultSecretDriver;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.parameters.spi.ParameterDriver;
import reactor.core.publisher.Mono;
import org.kamranzafar.cirrus.azure.redis.EntraIdRedisTokens;
import org.kamranzafar.cirrus.cache.CacheAuth;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.cache.spi.CacheDriver;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.HttpSettings;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.redis.RedisCaches;
import org.kamranzafar.cirrus.spi.ProviderConnection;
import org.kamranzafar.cirrus.storage.spi.BlobStoreDriver;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Owns the Azure SDK clients for one configuration. Clients are created lazily and shared by all containers,
 * and all of them send requests through one pooled HTTP client tuned by {@link HttpSettings}.
 */
final class AzureConnection implements ProviderConnection {

    private final ProviderConfig config;
    private HttpClient http;
    private BlobServiceClient blobService;
    private BlobServiceAsyncClient blobAsyncService;
    private RedisCaches caches;

    AzureConnection(ProviderConfig config) {
        this.config = config;
    }

    private synchronized HttpClient http() {
        if (http == null) {
            HttpSettings settings = HttpSettings.from(config);
            http = HttpClient.createDefault(new HttpClientOptions()
                    .setMaximumConnectionPoolSize(settings.maxConnections())
                    .setConnectTimeout(settings.connectTimeout())
                    .setReadTimeout(settings.readTimeout())
                    .setResponseTimeout(settings.readTimeout())
                    .setWriteTimeout(settings.readTimeout())
                    .setConnectionIdleTimeout(settings.idleTimeout()));
        }
        return http;
    }

    @Override
    public synchronized BlobStoreDriver blobStore(String container) {
        if (blobService == null) {
            var builder = new BlobServiceClientBuilder().endpoint(blobEndpoint()).httpClient(http());
            switch (config.credentials()) {
                case Credentials.DefaultChain ignored -> builder.credential(new DefaultAzureCredentialBuilder().build());
                case Credentials.Static s -> builder.credential(new StorageSharedKeyCredential(s.id(), s.secret()));
                case Credentials.Anonymous ignored -> { }
            }
            blobService = builder.buildClient();
            blobAsyncService = builder.buildAsyncClient();
        }
        boolean sharedKey = config.credentials() instanceof Credentials.Static;
        return new AzureBlobStoreDriver(container, blobService, blobAsyncService, sharedKey);
    }

    @Override
    public synchronized CacheDriver cache(CacheConfig cacheConfig) {
        if (caches == null) {
            caches = new RedisCaches(AzureProvider.ID);
        }
        return caches.driver(cacheConfig, RedisCaches.credentials(cacheConfig.auth(), this::entraTokens));
    }

    @Override
    public ParameterDriver parameterStore(ParameterStoreConfig storeConfig) {
        boolean insecureHttp = Boolean.parseBoolean(storeConfig.property(AzureParameterProperties.INSECURE_HTTP).orElse("false"));
        return switch (storeConfig.kind()) {
            case SECRETS -> {
                String vaultUrl = storeConfig.location().orElseThrow(() -> new IllegalArgumentException(
                        "Key Vault secrets need the vault URL as the store location, such as https://my-vault.vault.azure.net"));
                var builder = new SecretClientBuilder().vaultUrl(vaultUrl).credential(tokenCredential(insecureHttp))
                        .httpClient(http());
                if (insecureHttp) {
                    builder.addPolicy(new ForceHttpPolicy()).disableChallengeResourceVerification();
                }
                yield new KeyVaultSecretDriver(builder.buildClient(), storeConfig.nativeNames());
            }
            case PARAMETERS -> {
                var builder = new ConfigurationClientBuilder().httpClient(http());
                var connectionString = storeConfig.property(AzureParameterProperties.APPCONFIG_CONNECTION_STRING);
                if (connectionString.isPresent()) {
                    builder.connectionString(connectionString.get());
                } else {
                    builder.endpoint(storeConfig.location().orElseThrow(() -> new IllegalArgumentException(
                            "App Configuration needs its endpoint as the store location, or the '"
                                    + AzureParameterProperties.APPCONFIG_CONNECTION_STRING + "' property")))
                            .credential(tokenCredential(insecureHttp));
                }
                if (insecureHttp) {
                    builder.addPolicy(new ForceHttpPolicy());
                }
                yield new AppConfigurationDriver(builder.buildClient(),
                        storeConfig.property(AzureParameterProperties.APPCONFIG_LABEL).orElse(null), storeConfig.nativeNames());
            }
        };
    }

    /** Entra ID through the default chain; emulators accept any bearer token. */
    private TokenCredential tokenCredential(boolean emulator) {
        if (config.credentials() instanceof Credentials.DefaultChain) {
            return new DefaultAzureCredentialBuilder().build();
        }
        if (emulator && config.credentials() instanceof Credentials.Anonymous) {
            return request -> Mono.just(new AccessToken("emulator", OffsetDateTime.now().plusHours(1)));
        }
        throw new IllegalArgumentException("Key Vault and App Configuration use Entra ID: configure the provider with "
                + "Credentials.defaultChain(), or use an App Configuration connection string");
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
