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
package dev.halyx.azure;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
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
import dev.halyx.azure.blob.AzureBlobStoreDriver;
import dev.halyx.azure.cosmos.CosmosDocumentStoreDriver;
import dev.halyx.azure.eventhubs.EventHubsEntraTokens;
import dev.halyx.azure.parameters.AppConfigurationDriver;
import dev.halyx.azure.parameters.AzureParameterProperties;
import dev.halyx.azure.parameters.ForceHttpPolicy;
import dev.halyx.azure.parameters.KeyVaultSecretDriver;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.parameters.spi.ParameterDriver;
import reactor.core.publisher.Mono;
import dev.halyx.azure.redis.EntraIdRedisTokens;
import dev.halyx.azure.servicebus.ServiceBusPublisherDriver;
import dev.halyx.azure.servicebus.ServiceBusSessionSubscriptionDriver;
import dev.halyx.azure.servicebus.ServiceBusSubscriptionDriver;
import dev.halyx.azure.sql.EntraIdSqlTokens;
import dev.halyx.cache.CacheAuth;
import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.config.Credentials;
import dev.halyx.config.HttpSettings;
import dev.halyx.config.ProviderConfig;
import dev.halyx.jdbc.JdbcDatabases;
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
import dev.halyx.storage.spi.BlobStoreDriver;
import dev.halyx.capability.Capabilities;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.ProducerDriver;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private CosmosClient cosmos;
    /** Senders and receivers own AMQP links, closed with the connection. */
    private final List<AutoCloseable> serviceBusClients = new ArrayList<>();
    private BlobServiceClient blobService;
    private BlobServiceAsyncClient blobAsyncService;
    private RedisCaches caches;
    private KafkaStreamClients streams;

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

    /**
     * Event Hubs through its Kafka endpoint. Neither idempotent nor transactional producers are declared: Event
     * Hubs offers transactions only on its premium and dedicated tiers, and idempotence is unconfirmed.
     */
    private static final Capabilities EVENT_HUBS_CAPABILITIES = Capabilities.none();

    /**
     * Client settings Microsoft recommends for Event Hubs, which closes connections idle for 240 seconds and
     * accepts requests up to 1 MB; idempotence is off to match the capabilities. Stream properties override them.
     */
    static final Map<String, String> EVENT_HUBS_DEFAULTS = Map.of(
            "connections.max.idle.ms", "180000",
            "metadata.max.age.ms", "180000",
            "request.timeout.ms", "60000",
            "max.request.size", "1046528",
            "enable.idempotence", "false");

    @Override
    public synchronized ProducerDriver streamProducer(StreamProducerConfig producer) {
        return streams().producer(producer, eventHubsCredentials(producer.cluster()), EVENT_HUBS_CAPABILITIES,
                EVENT_HUBS_DEFAULTS);
    }

    @Override
    public synchronized ConsumerDriver streamConsumer(StreamConsumerConfig consumer) {
        return streams().consumer(consumer, eventHubsCredentials(consumer.cluster()), EVENT_HUBS_CAPABILITIES,
                EVENT_HUBS_DEFAULTS);
    }

    private KafkaStreamClients streams() {
        if (streams == null) {
            streams = new KafkaStreamClients(AzureProvider.ID);
        }
        return streams;
    }

    /** Entra ID tokens for the namespace, through the default credential chain. */
    private KafkaCredentialsSource eventHubsCredentials(StreamConfig cluster) {
        return KafkaStreamClients.credentials(cluster.auth(), () -> {
            if (!(config.credentials() instanceof Credentials.DefaultChain)) {
                throw new IllegalArgumentException("Entra ID login to Event Hubs uses the default Azure credential "
                        + "chain; configure the provider with Credentials.defaultChain()");
            }
            String scope = cluster.property(AzureProperties.EVENT_HUBS_SCOPE)
                    .orElseGet(() -> EventHubsEntraTokens.scope(cluster.bootstrapServerList().getFirst()));
            return new EventHubsEntraTokens(new DefaultAzureCredentialBuilder().build(), scope);
        });
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

    /** A Cosmos DB container in {@code azure.cosmos.database}. */
    @Override
    public synchronized DocumentStoreDriver documentStore(TableConfig table) {
        if (cosmos == null) {
            String endpoint = config.property(AzureProperties.COSMOS_ENDPOINT)
                    .orElseThrow(() -> new IllegalArgumentException("Cosmos DB needs '" + AzureProperties.COSMOS_ENDPOINT + "'"));
            var builder = new CosmosClientBuilder().endpoint(endpoint).gatewayMode().endpointDiscoveryEnabled(false);
            var key = config.property(AzureProperties.COSMOS_KEY);
            if (key.isPresent()) {
                builder.key(key.get());
            } else {
                builder.credential(new DefaultAzureCredentialBuilder().build());
            }
            cosmos = builder.buildClient();
        }
        String database = config.property(AzureProperties.COSMOS_DATABASE)
                .orElseThrow(() -> new IllegalArgumentException("Cosmos DB needs '" + AzureProperties.COSMOS_DATABASE + "'"));
        return new CosmosDocumentStoreDriver(cosmos.getDatabase(database).getContainer(table.name()), table);
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
        boolean sessions = topic.property(AzureProperties.SERVICE_BUS_SESSIONS).map(Boolean::parseBoolean).orElse(false);
        var driver = new ServiceBusPublisherDriver(sender.buildClient(), topic.name(), maxBytes, sessions);
        serviceBusClients.add(driver);
        return driver;
    }

    /** A Service Bus queue, or a topic subscription named {@code <topic>/subscriptions/<subscription>}. */
    @Override
    public synchronized SubscriptionDriver subscription(SubscriptionConfig subscription) {
        if (subscription.property(AzureProperties.SERVICE_BUS_SESSIONS).map(Boolean::parseBoolean).orElse(false)) {
            var sessions = serviceBus().sessionReceiver().receiveMode(ServiceBusReceiveMode.PEEK_LOCK)
                    .disableAutoComplete().maxAutoLockRenewDuration(Duration.ZERO);
            String[] parts = subscription.name().split("/subscriptions/", 2);
            if (parts.length == 2) {
                sessions.topicName(parts[0]).subscriptionName(parts[1]);
            } else {
                sessions.queueName(subscription.name());
            }
            int max = subscription.property(AzureProperties.SERVICE_BUS_MAX_SESSIONS).map(Integer::parseInt).orElse(8);
            var driver = new ServiceBusSessionSubscriptionDriver(sessions.buildClient(), subscription.name(), max);
            serviceBusClients.add(driver);
            return driver;
        }
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
        if (streams != null) {
            streams.close();
            streams = null;
        }
        if (databases != null) {
            databases.close();
            databases = null;
        }
        if (cosmos != null) {
            cosmos.close();
            cosmos = null;
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
