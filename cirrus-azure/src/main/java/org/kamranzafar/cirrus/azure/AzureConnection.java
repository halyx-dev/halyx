package org.kamranzafar.cirrus.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
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
import org.kamranzafar.cirrus.azure.servicebus.ServiceBusPublisherDriver;
import org.kamranzafar.cirrus.azure.servicebus.ServiceBusSubscriptionDriver;
import org.kamranzafar.cirrus.azure.sql.EntraIdSqlTokens;
import org.kamranzafar.cirrus.cache.CacheAuth;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.cache.spi.CacheDriver;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.HttpSettings;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.jdbc.JdbcDatabases;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.messaging.spi.PublisherDriver;
import org.kamranzafar.cirrus.messaging.spi.SubscriptionDriver;
import org.kamranzafar.cirrus.redis.RedisCaches;
import org.kamranzafar.cirrus.spi.ProviderConnection;
import org.kamranzafar.cirrus.sql.Database;
import org.kamranzafar.cirrus.sql.SqlAuth;
import org.kamranzafar.cirrus.sql.SqlConfig;
import org.kamranzafar.cirrus.storage.spi.BlobStoreDriver;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owns the Azure SDK clients for one configuration. Clients are created lazily and shared by all containers,
 * and all of them send requests through one pooled HTTP client tuned by {@link HttpSettings}.
 */
final class AzureConnection implements ProviderConnection {

    private final ProviderConfig config;
    private HttpClient http;
    private JdbcDatabases databases;
    private ServiceBusClientBuilder serviceBus;
    /** Senders and receivers own AMQP links, closed with the connection. */
    private final List<AutoCloseable> serviceBusClients = new ArrayList<>();
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

    /** A Service Bus topic, or with {@code azure.servicebus.entity=queue} a queue. */
    @Override
    public synchronized PublisherDriver publisher(TopicConfig topic) {
        String entity = topic.property(AzureProperties.SERVICE_BUS_ENTITY).orElse("topic");
        var sender = serviceBus().sender();
        switch (entity) {
            case "topic" -> sender.topicName(topic.name());
            case "queue" -> sender.queueName(topic.name());
            default -> throw new IllegalArgumentException(AzureProperties.SERVICE_BUS_ENTITY + " must be topic or queue, not " + entity);
        }
        long maxBytes = config.property(AzureProperties.SERVICE_BUS_MAX_MESSAGE_BYTES).map(Long::parseLong).orElse(256L * 1024);
        var driver = new ServiceBusPublisherDriver(sender.buildClient(), topic.name(), maxBytes);
        serviceBusClients.add(driver);
        return driver;
    }

    /** A Service Bus queue, or a topic subscription named {@code <topic>/subscriptions/<subscription>}. */
    @Override
    public synchronized SubscriptionDriver subscription(SubscriptionConfig subscription) {
        var receiver = serviceBus().receiver().receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                .disableAutoComplete().maxAutoLockRenewDuration(Duration.ZERO);
        String[] parts = subscription.name().split("/subscriptions/", 2);
        if (parts.length == 2) {
            receiver.topicName(parts[0]).subscriptionName(parts[1]);
        } else {
            receiver.queueName(subscription.name());
        }
        var driver = new ServiceBusSubscriptionDriver(receiver.buildClient(), subscription.name());
        serviceBusClients.add(driver);
        return driver;
    }

    /** One builder for every sender and receiver, so they share one AMQP connection. */
    private ServiceBusClientBuilder serviceBus() {
        if (serviceBus == null) {
            var builder = new ServiceBusClientBuilder();
            var connectionString = config.property(AzureProperties.SERVICE_BUS_CONNECTION_STRING);
            if (connectionString.isPresent()) {
                builder.connectionString(connectionString.get());
            } else {
                String namespace = config.property(AzureProperties.SERVICE_BUS_NAMESPACE).orElseThrow(() ->
                        new IllegalArgumentException("Service Bus needs '" + AzureProperties.SERVICE_BUS_CONNECTION_STRING
                                + "' or '" + AzureProperties.SERVICE_BUS_NAMESPACE + "'"));
                builder.fullyQualifiedNamespace(namespace).credential(new DefaultAzureCredentialBuilder().build());
            }
            serviceBus = builder;
        }
        return serviceBus;
    }

    /** Password auth, or Entra ID tokens (Azure Database for PostgreSQL and MySQL, Azure SQL). */
    @Override
    public synchronized Database database(SqlConfig sql) {
        if (databases == null) {
            databases = new JdbcDatabases();
        }
        return switch (sql.auth()) {
            case SqlAuth.Password ignored -> databases.createWithPassword(sql);
            case SqlAuth.Iam iam -> {
                if (config.credentials() instanceof Credentials.Static) {
                    throw new IllegalArgumentException("Entra ID database login needs an identity "
                            + "(Credentials.defaultChain()), not a storage shared key");
                }
                var tokens = new EntraIdSqlTokens(new DefaultAzureCredentialBuilder().build(), sql.engine(),
                        iam.username().orElse(null));
                yield databases.create(sql, sql.jdbcUrl(), tokens, JdbcDatabases.requireTls(sql.engine()));
            }
        };
    }

    @Override
    public synchronized void close() {
        if (databases != null) {
            databases.close();
            databases = null;
        }
        for (AutoCloseable client : serviceBusClients) {
            try {
                client.close();
            } catch (Exception ignored) {
                // closing anyway
            }
        }
        serviceBusClients.clear();
        serviceBus = null;
        // The sync Azure clients hold no resources that need explicit release.
        blobService = null;
        if (caches != null) {
            caches.close();
            caches = null;
        }
    }
}
