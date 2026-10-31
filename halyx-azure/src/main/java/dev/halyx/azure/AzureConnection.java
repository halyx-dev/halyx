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

import com.azure.cosmos.CosmosAsyncClient;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosClientBuilder;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.eventgrid.namespaces.EventGridReceiverClientBuilder;
import com.azure.messaging.eventgrid.namespaces.EventGridSenderClientBuilder;
import dev.halyx.azure.eventgrid.EventGridPublisherDriver;
import dev.halyx.azure.eventgrid.EventGridSubscriptionDriver;
import dev.halyx.events.EventBusConfig;
import dev.halyx.events.spi.EventPublisherDriver;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.models.ServiceBusReceiveMode;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpHeaderName;
import com.azure.core.http.HttpHeaders;
import com.azure.core.http.HttpPipelineBuilder;
import com.azure.core.http.policy.AddHeadersPolicy;
import com.azure.core.http.policy.BearerTokenAuthenticationPolicy;
import com.azure.core.http.policy.ExponentialBackoffOptions;
import com.azure.core.http.policy.HttpPipelinePolicy;
import com.azure.core.http.policy.RetryOptions;
import com.azure.core.http.policy.RetryPolicy;
import com.azure.core.util.HttpClientOptions;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.security.keyvault.secrets.SecretClientBuilder;
import dev.halyx.azure.blob.AzureBlobStoreDriver;
import dev.halyx.azure.cosmos.CosmosDocumentStoreDriver;
import dev.halyx.azure.eventhubs.EventHubsEntraTokens;
import dev.halyx.azure.functions.AzureFunctionsInvokerDriver;
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
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.spi.ConnectFailures;
import dev.halyx.functions.spi.FunctionInvokerDriver;
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
import dev.halyx.sql.SqlEndpoint;
import dev.halyx.storage.spi.BlobStoreDriver;
import dev.halyx.capability.Capabilities;
import dev.halyx.stream.StreamCapability;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.ProducerDriver;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
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
    private TokenCredential entraCredential;
    private HttpClient http;
    private JdbcDatabases databases;
    private ServiceBusClientBuilder serviceBus;
    private CosmosClient cosmos;
    private CosmosClientBuilder cosmosBuilder;
    private CosmosAsyncClient cosmosAsync;
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
                case Credentials.DefaultChain ignored -> builder.credential(entraCredential());
                case Credentials.ServicePrincipal ignored -> builder.credential(entraCredential());
                case Credentials.Static s -> builder.credential(new StorageSharedKeyCredential(s.id(), s.secret()));
                case Credentials.Anonymous ignored -> { }
                case Credentials.Impersonation ignored -> builder.credential(entraCredential()); // refused there
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
                yield new KeyVaultSecretDriver(builder.buildClient(), builder.buildAsyncClient(), storeConfig.nativeNames());
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
                yield new AppConfigurationDriver(builder.buildClient(), builder.buildAsyncClient(),
                        storeConfig.property(AzureParameterProperties.APPCONFIG_LABEL).orElse(null), storeConfig.nativeNames());
            }
        };
    }

    /**
     * The Entra ID credential, made once and shared by every client, so tokens are cached once: a configured
     * service principal, or otherwise the default chain.
     */
    private synchronized TokenCredential entraCredential() {
        if (entraCredential == null) {
            entraCredential = switch (config.credentials()) {
                case Credentials.ServicePrincipal principal -> EntraCredentials.servicePrincipal(principal);
                case Credentials.Impersonation ignored -> throw new IllegalArgumentException("Azure logs in with "
                        + "Entra ID, not by impersonation: use the default chain or Credentials.servicePrincipal(...)");
                default -> new DefaultAzureCredentialBuilder().build();
            };
        }
        return entraCredential;
    }

    /** Whether the provider logs in with Entra ID: the default chain or a service principal. */
    private boolean entraId() {
        return config.credentials() instanceof Credentials.DefaultChain
                || config.credentials() instanceof Credentials.ServicePrincipal;
    }

    /** Entra ID through the default chain or a service principal; emulators accept any bearer token. */
    private TokenCredential tokenCredential(boolean emulator) {
        if (entraId()) {
            return entraCredential();
        }
        if (emulator && config.credentials() instanceof Credentials.Anonymous) {
            return request -> Mono.just(new AccessToken("emulator", OffsetDateTime.now().plusHours(1)));
        }
        throw new IllegalArgumentException("Key Vault and App Configuration use Entra ID: configure the provider with "
                + "Credentials.defaultChain() or a service principal, or use an App Configuration connection string");
    }

    /**
     * Event Hubs through its Kafka endpoint: idempotent producers, confirmed on a Standard namespace. Transactions
     * aren't declared: Event Hubs offers them only on its premium and dedicated tiers, as a preview.
     */
    private static final Capabilities EVENT_HUBS_CAPABILITIES = Capabilities.of(StreamCapability.IDEMPOTENCE);

    /**
     * Client settings Microsoft recommends for Event Hubs, which closes connections idle for 240 seconds and
     * accepts requests up to 1 MB. Idempotence keeps Kafka's default (on). Stream properties override them.
     */
    static final Map<String, String> EVENT_HUBS_DEFAULTS = Map.of(
            "connections.max.idle.ms", "180000",
            "metadata.max.age.ms", "180000",
            "request.timeout.ms", "60000",
            "max.request.size", "1046528");

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

    @Override
    public Map<String, Object> kafkaClientProperties(StreamConfig cluster) {
        return KafkaStreamClients.clientProperties(cluster, eventHubsCredentials(cluster), EVENT_HUBS_DEFAULTS);
    }

    private KafkaStreamClients streams() {
        if (streams == null) {
            streams = new KafkaStreamClients(AzureProvider.ID);
        }
        return streams;
    }

    /** Entra ID tokens for the namespace, through the provider's Entra ID credential. */
    private KafkaCredentialsSource eventHubsCredentials(StreamConfig cluster) {
        return KafkaStreamClients.credentials(cluster.auth(), () -> {
            if (!entraId()) {
                throw new IllegalArgumentException("Entra ID login to Event Hubs needs an identity: configure the "
                        + "provider with Credentials.defaultChain() or a service principal");
            }
            String scope = cluster.property(AzureProperties.EVENT_HUBS_SCOPE)
                    .orElseGet(() -> EventHubsEntraTokens.scope(cluster.bootstrapServerList().getFirst()));
            return new EventHubsEntraTokens(entraCredential(), scope);
        });
    }

    private EntraIdRedisTokens entraTokens(CacheAuth.Iam iam) {
        if (!entraId()) {
            throw new IllegalArgumentException("Entra ID cache authentication needs an identity: configure the "
                    + "provider with Credentials.defaultChain() or a service principal");
        }
        return new EntraIdRedisTokens(entraCredential(), iam.username());
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

    /** The non-blocking Cosmos DB client, made with the same settings on the first asynchronous call. */
    private synchronized CosmosAsyncClient cosmosAsync() {
        if (cosmosAsync == null) {
            cosmosAsync = cosmosBuilder.buildAsyncClient();
        }
        return cosmosAsync;
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
                builder.credential(entraCredential());
            }
            cosmos = builder.buildClient();
            cosmosBuilder = builder;
        }
        String database = config.property(AzureProperties.COSMOS_DATABASE)
                .orElseThrow(() -> new IllegalArgumentException("Cosmos DB needs '" + AzureProperties.COSMOS_DATABASE + "'"));
        return new CosmosDocumentStoreDriver(cosmos.getDatabase(database).getContainer(table.name()), table,
                () -> cosmosAsync().getDatabase(database).getContainer(table.name()));
    }

    /** An Event Grid namespace topic, at the namespace endpoint {@code azure.eventgrid.endpoint}. */
    @Override
    public synchronized EventPublisherDriver eventPublisher(EventBusConfig bus) {
        String endpoint = bus.property(AzureProperties.EVENT_GRID_ENDPOINT).orElseThrow(() -> new IllegalArgumentException(
                "An Event Grid event bus needs its namespace endpoint: property " + AzureProperties.EVENT_GRID_ENDPOINT));
        var sender = new EventGridSenderClientBuilder().endpoint(endpoint).topicName(bus.name());
        bus.property(AzureProperties.EVENT_GRID_ACCESS_KEY).ifPresentOrElse(
                key -> sender.credential(new AzureKeyCredential(key)), () -> sender.credential(tokenCredential(false)));
        return new EventGridPublisherDriver(sender.buildClient(), sender.buildAsyncClient(), bus.name());
    }

    /**
     * Function invocations that may run the function twice are retried only after a 429 or a failure to connect,
     * when the Functions host hasn't run it.
     */
    private static final HttpHeaderName FUNCTIONS_KEY_HEADER = HttpHeaderName.fromString("x-functions-key");

    private static final RetryOptions BEFORE_SEND_RETRIES = new RetryOptions(new ExponentialBackoffOptions())
            .setShouldRetryCondition(attempt -> attempt.getResponse() != null && attempt.getResponse().getStatusCode() == 429
                    || attempt.getThrowable() != null && ConnectFailures.isConnectFailure(attempt.getThrowable()));

    /**
     * An HTTP-triggered function on the app at {@code azure.functions.endpoint}, logging in with an Entra ID token for
     * {@code azure.functions.audience} or with the function key {@code azure.functions.key}.
     */
    @Override
    public synchronized FunctionInvokerDriver functionInvoker(FunctionConfig function) {
        String endpoint = function.property(AzureProperties.FUNCTIONS_ENDPOINT).orElseThrow(() -> new IllegalArgumentException(
                "Azure function " + function.name() + " needs its function app's URL: property " + AzureProperties.FUNCTIONS_ENDPOINT));
        String route = function.property(AzureProperties.FUNCTIONS_ROUTE).orElse("api/" + function.name());
        URL url;
        try {
            url = URI.create(endpoint.replaceAll("/+$", "") + "/" + route.replaceAll("^/+", "")).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Azure function " + function.name() + " has no valid URL from "
                    + AzureProperties.FUNCTIONS_ENDPOINT + " '" + endpoint + "' and route '" + route + "'", e);
        }
        List<HttpPipelinePolicy> policies = new ArrayList<>();
        policies.add(functionRetries(function.idempotent()));
        functionLogin(function).ifPresent(policies::add);
        var pipeline = new HttpPipelineBuilder().httpClient(http()).policies(policies.toArray(HttpPipelinePolicy[]::new)).build();
        return new AzureFunctionsInvokerDriver(pipeline, url, function);
    }

    /** The connection's SDK clients use the default retry policy; idempotent functions do too. */
    static RetryPolicy functionRetries(boolean idempotent) {
        return idempotent ? new RetryPolicy() : new RetryPolicy(BEFORE_SEND_RETRIES);
    }

    /** An Entra ID token for the audience, or the function key; neither for an anonymous provider, such as an emulator. */
    private Optional<HttpPipelinePolicy> functionLogin(FunctionConfig function) {
        Optional<String> audience = function.property(AzureProperties.FUNCTIONS_AUDIENCE);
        Optional<String> key = function.property(AzureProperties.FUNCTIONS_KEY);
        if (audience.isPresent() && key.isPresent()) {
            throw new IllegalArgumentException("Azure function " + function.name() + " has both "
                    + AzureProperties.FUNCTIONS_AUDIENCE + " and " + AzureProperties.FUNCTIONS_KEY + ": set one");
        }
        if (key.isPresent()) {
            return Optional.of(new AddHeadersPolicy(new HttpHeaders().set(FUNCTIONS_KEY_HEADER, key.get())));
        }
        if (audience.isPresent()) {
            if (!entraId()) {
                throw new IllegalArgumentException("Azure function " + function.name() + " logs in to "
                        + AzureProperties.FUNCTIONS_AUDIENCE + " with Entra ID: configure the provider with "
                        + "Credentials.defaultChain() or a service principal, or use " + AzureProperties.FUNCTIONS_KEY);
            }
            String scope = audience.get().endsWith("/.default") ? audience.get() : audience.get() + "/.default";
            return Optional.of(new BearerTokenAuthenticationPolicy(entraCredential(), scope));
        }
        if (config.credentials() instanceof Credentials.Anonymous) {
            return Optional.empty();
        }
        throw new IllegalArgumentException("Azure function " + function.name() + " needs a login: property "
                + AzureProperties.FUNCTIONS_AUDIENCE + " (Entra ID) or " + AzureProperties.FUNCTIONS_KEY + " (a function key)");
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
        var driver = new ServiceBusPublisherDriver(sender.buildClient(), sender::buildAsyncClient, topic.name(), maxBytes,
                sessions);
        serviceBusClients.add(driver);
        return driver;
    }

    /** A Service Bus queue, or a topic subscription named {@code <topic>/subscriptions/<subscription>}. */
    @Override
    public synchronized SubscriptionDriver subscription(SubscriptionConfig subscription) {
        if (subscription.property(AzureProperties.EVENT_GRID_ENDPOINT).isPresent()) {
            String topic = subscription.topic().orElseThrow(() -> new IllegalArgumentException("An Event Grid pull "
                    + "subscription needs its namespace topic: SubscriptionConfig.builder(name).topic(topic)"));
            var receiver = new EventGridReceiverClientBuilder()
                    .endpoint(subscription.property(AzureProperties.EVENT_GRID_ENDPOINT).orElseThrow())
                    .topicName(topic).subscriptionName(subscription.name());
            subscription.property(AzureProperties.EVENT_GRID_ACCESS_KEY).ifPresentOrElse(
                    key -> receiver.credential(new AzureKeyCredential(key)), () -> receiver.credential(tokenCredential(false)));
            Duration lock = subscription.property(AzureProperties.EVENT_GRID_LOCK_DURATION).map(Duration::parse)
                    .orElse(Duration.ofSeconds(60));
            return new EventGridSubscriptionDriver(receiver.buildClient(), receiver.buildAsyncClient(), subscription.name(), lock);
        }
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
                builder.fullyQualifiedNamespace(namespace).credential(entraCredential());
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
            case SqlAuth.Iam iam -> databases.create(sql, sql.jdbcUrl(), entraIdTokens(sql, iam),
                    JdbcDatabases.requireTls(sql.engine()));
        };
    }

    @Override
    public SqlEndpoint sqlEndpoint(SqlConfig sql) {
        String host = sql.host().orElseThrow(() -> new IllegalArgumentException("Azure databases need a host"));
        return switch (sql.auth()) {
            case SqlAuth.Password password -> new SqlEndpoint(sql.engine(), host, sql.port(), sql.database(),
                    JdbcDatabases.passwordSource(password), false);
            case SqlAuth.Iam iam -> new SqlEndpoint(sql.engine(), host, sql.port(), sql.database(),
                    entraIdTokens(sql, iam), true);
        };
    }

    /** Entra ID tokens for the database, through the provider's Entra ID credential. */
    private EntraIdSqlTokens entraIdTokens(SqlConfig sql, SqlAuth.Iam iam) {
        if (config.credentials() instanceof Credentials.Static) {
            throw new IllegalArgumentException("Entra ID database login needs an identity "
                    + "(Credentials.defaultChain() or a service principal), not a storage shared key");
        }
        return new EntraIdSqlTokens(entraCredential(), sql.engine(), iam.username().orElse(null));
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
        if (cosmosAsync != null) {
            cosmosAsync.close();
            cosmosAsync = null;
        }
        cosmosBuilder = null;
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
