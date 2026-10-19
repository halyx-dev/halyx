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
package dev.halyx.gcp;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.eventarc.publishing.v1.PublisherClient;
import com.google.cloud.eventarc.publishing.v1.PublisherSettings;
import dev.halyx.events.EventBusConfig;
import dev.halyx.events.spi.EventPublisherDriver;
import dev.halyx.gcp.eventarc.EventarcPublisherDriver;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.ClientSettings;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.NoCredentials;
import com.google.cloud.ServiceOptions;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import com.google.cloud.parametermanager.v1.ParameterManagerClient;
import com.google.cloud.parametermanager.v1.ParameterManagerSettings;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import io.grpc.ManagedChannelBuilder;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.cloud.http.HttpTransportOptions;
import com.google.api.client.http.apache.v2.ApacheHttpTransport;
import dev.halyx.config.Credentials;
import dev.halyx.config.HttpSettings;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.jdbc.JdbcDatabases;
import dev.halyx.kafka.KafkaCredentialsSource;
import dev.halyx.kafka.KafkaStreamClients;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.spi.DocumentStoreDriver;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.messaging.spi.PublisherDriver;
import dev.halyx.messaging.spi.SubscriptionDriver;
import dev.halyx.cache.CacheAuth;
import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.gcp.firestore.FirestoreDocumentStoreDriver;
import dev.halyx.gcp.memorystore.MemorystoreIamTokens;
import dev.halyx.gcp.parameters.ParameterManagerDriver;
import dev.halyx.gcp.pubsub.PubSubPublisherDriver;
import dev.halyx.gcp.pubsub.PubSubSubscriptionDriver;
import dev.halyx.gcp.parameters.SecretManagerDriver;
import dev.halyx.gcp.kafka.ManagedKafkaTokens;
import dev.halyx.gcp.sql.CloudSqlConnector;
import dev.halyx.gcp.sql.CloudSqlIamTokens;
import dev.halyx.gcp.storage.GcsBlobStoreDriver;
import dev.halyx.parameters.ParameterKind;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.parameters.spi.ParameterDriver;
import dev.halyx.redis.RedisCaches;
import dev.halyx.spi.ProviderConnection;
import dev.halyx.sql.Database;
import dev.halyx.sql.SqlAuth;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEndpoint;
import dev.halyx.sql.SqlEngine;
import dev.halyx.sql.spi.SqlCredentialsSource;
import dev.halyx.sql.spi.SqlLogin;
import dev.halyx.storage.spi.BlobStoreDriver;
import dev.halyx.capability.Capabilities;
import dev.halyx.stream.StreamCapability;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.ProducerDriver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Owns the Google Cloud clients for one configuration. Clients are created lazily and shared by all buckets. */
final class GcpConnection implements ProviderConnection {

    private final ProviderConfig config;
    private ApacheHttpTransport httpTransport;
    private Storage storage;
    private JdbcDatabases databases;
    private TopicAdminClient topicAdmin;
    private Firestore firestore;
    private SubscriptionAdminClient subscriptionAdmin;
    private RedisCaches caches;
    private PublisherClient eventarc;
    private KafkaStreamClients streams;
    private SecretManagerServiceClient secretManager;
    private final Map<String, ParameterManagerClient> parameterManagers = new HashMap<>();
    private GoogleCredentials googleCredentials;

    GcpConnection(ProviderConfig config) {
        this.config = config;
    }

    @Override
    public synchronized BlobStoreDriver blobStore(String bucket) {
        if (storage == null) {
            HttpSettings settings = HttpSettings.from(config);
            httpTransport = new ApacheHttpTransport(ApacheHttpTransport.newDefaultHttpClientBuilder()
                    .setMaxConnTotal(settings.maxConnections())
                    .setMaxConnPerRoute(settings.maxConnections())
                    .evictIdleConnections(settings.idleTimeout().toMillis(), TimeUnit.MILLISECONDS)
                    // Return bytes exactly as stored: never inflate gzip-encoded objects in the client.
                    .disableContentCompression()
                    .build());
            var builder = StorageOptions.http().setTransportOptions(HttpTransportOptions.newBuilder()
                    .setHttpTransportFactory(() -> httpTransport)
                    .setConnectTimeout(Math.toIntExact(settings.connectTimeout().toMillis()))
                    .setReadTimeout(Math.toIntExact(settings.readTimeout().toMillis()))
                    .build());
            config.property(GcpProperties.PROJECT_ID).ifPresent(builder::setProjectId);
            config.endpoint().ifPresent(endpoint -> builder.setHost(endpoint.toString()));
            switch (config.credentials()) {
                case Credentials.DefaultChain ignored -> { } // Application Default Credentials
                case Credentials.Anonymous ignored -> builder.setCredentials(NoCredentials.getInstance());
                default -> builder.setCredentials(googleCredentials());
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

    @Override
    public synchronized ParameterDriver parameterStore(ParameterStoreConfig storeConfig) {
        String project = config.property(GcpProperties.PROJECT_ID).orElseGet(ServiceOptions::getDefaultProjectId);
        if (project == null) {
            throw new IllegalArgumentException("Parameter stores need a project: set the '" + GcpProperties.PROJECT_ID + "' property");
        }
        if (storeConfig.kind() == ParameterKind.PARAMETERS) {
            // The store location is the Parameter Manager location: "global" or a region such as "europe-west2".
            String location = storeConfig.location().orElse("global");
            ParameterManagerClient client = parameterManagers.computeIfAbsent(location, this::parameterManagerClient);
            return new ParameterManagerDriver(client, project, location,
                    Boolean.parseBoolean(storeConfig.property(ParameterManagerDriver.RENDER).orElse("false")),
                    storeConfig.nativeNames());
        }
        if (secretManager == null) {
            secretManager = secretManagerClient();
        }
        return new SecretManagerDriver(secretManager, project, storeConfig.nativeNames());
    }

    /** Regional locations are served by regional endpoints; an endpoint override (emulator or fake) wins. */
    private ParameterManagerClient parameterManagerClient(String location) {
        try {
            var settings = ParameterManagerSettings.newBuilder();
            if (config.endpoint().isPresent()) {
                var endpoint = config.endpoint().get();
                var channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
                settings.setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                        .setCredentialsProvider(NoCredentialsProvider.create());
            } else {
                if (!"global".equals(location)) {
                    settings.setEndpoint("parametermanager." + location + ".rep.googleapis.com:443");
                }
                credentialsProvider().ifPresent(settings::setCredentialsProvider);
            }
            return ParameterManagerClient.create(settings.build());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the Parameter Manager client", e);
        }
    }

    /** With an endpoint override (an emulator), Secret Manager is reached over plaintext gRPC on the same host and port. */
    /** A Firestore collection; with an endpoint override, the Firestore emulator at that host and port. */
    @Override
    public synchronized DocumentStoreDriver documentStore(TableConfig table) {
        if (firestore == null) {
            var options = FirestoreOptions.newBuilder();
            config.property(GcpProperties.PROJECT_ID).ifPresent(options::setProjectId);
            config.property(GcpProperties.FIRESTORE_DATABASE).ifPresent(options::setDatabaseId);
            if (config.endpoint().isPresent()) {
                var endpoint = config.endpoint().get();
                options.setEmulatorHost(endpoint.getHost() + ":" + endpoint.getPort());
            } else if (config.credentials() instanceof Credentials.Anonymous) {
                options.setCredentials(NoCredentials.getInstance());
            } else if (!(config.credentials() instanceof Credentials.DefaultChain)) {
                options.setCredentials(googleCredentials());
            }
            firestore = options.build().getService();
        }
        return new FirestoreDocumentStoreDriver(firestore, table);
    }

    /** A Pub/Sub topic: a topic id in the configured project, or {@code projects/<p>/topics/<id>}. */
    @Override
    public synchronized PublisherDriver publisher(TopicConfig topic) {
        if (topicAdmin == null) {
            topicAdmin = pubSubClient(TopicAdminSettings.newBuilder(), TopicAdminClient::create);
        }
        return new PubSubPublisherDriver(topicAdmin, qualified(topic.name(), "topics"));
    }

    /** A Pub/Sub pull subscription: an id in the configured project, or {@code projects/<p>/subscriptions/<id>}. */
    @Override
    public synchronized SubscriptionDriver subscription(SubscriptionConfig subscription) {
        if (subscriptionAdmin == null) {
            subscriptionAdmin = pubSubClient(SubscriptionAdminSettings.newBuilder(), SubscriptionAdminClient::create);
        }
        return new PubSubSubscriptionDriver(subscriptionAdmin, qualified(subscription.name(), "subscriptions"));
    }

    @Override
    public synchronized EventPublisherDriver eventPublisher(EventBusConfig bus) {
        String name = bus.name();
        if (!name.startsWith("projects/")) {
            String project = config.property(GcpProperties.PROJECT_ID).orElseGet(ServiceOptions::getDefaultProjectId);
            String location = bus.property(GcpProperties.EVENTARC_LOCATION).orElseThrow(() -> new IllegalArgumentException(
                    "Event bus " + bus.name() + " needs its location: property " + GcpProperties.EVENTARC_LOCATION
                            + ", or name it projects/<project>/locations/<location>/messageBuses/<id>"));
            if (project == null) {
                throw new IllegalArgumentException("Eventarc needs a project: set the '" + GcpProperties.PROJECT_ID + "' property");
            }
            name = "projects/" + project + "/locations/" + location + "/messageBuses/" + name;
        }
        if (eventarc == null) {
            // Message buses are published to through the global endpoint, whatever their location; the regional
            // <location>-eventarcpublishing endpoints serve only Eventarc Standard channels.
            try {
                var settings = PublisherSettings.newBuilder();
                credentialsProvider().ifPresent(settings::setCredentialsProvider);
                eventarc = PublisherClient.create(settings.build());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not create the Eventarc publishing client", e);
            }
        }
        return new EventarcPublisherDriver(eventarc, name);
    }

    private String qualified(String name, String collection) {
        if (name.startsWith("projects/")) {
            return name;
        }
        String project = config.property(GcpProperties.PROJECT_ID).orElseGet(ServiceOptions::getDefaultProjectId);
        if (project == null) {
            throw new IllegalArgumentException("Pub/Sub needs a project: set the '" + GcpProperties.PROJECT_ID
                    + "' property or use projects/<project>/" + collection + "/<id>");
        }
        return "projects/" + project + "/" + collection + "/" + name;
    }

    @FunctionalInterface
    private interface ClientFactory<S, C> {
        C create(S settings) throws IOException;
    }

    private <B extends ClientSettings.Builder<S, B>, S extends ClientSettings<S>, C> C pubSubClient(B settings, ClientFactory<S, C> factory) {
        try {
            if (config.endpoint().isPresent()) {
                var endpoint = config.endpoint().get();
                var channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
                settings.setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                        .setCredentialsProvider(NoCredentialsProvider.create());
            } else {
                credentialsProvider().ifPresent(settings::setCredentialsProvider);
            }
            return factory.create(settings.build());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the Pub/Sub client", e);
        }
    }

    private SecretManagerServiceClient secretManagerClient() {
        try {
            var settings = SecretManagerServiceSettings.newBuilder();
            if (config.endpoint().isPresent()) {
                var endpoint = config.endpoint().get();
                var channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
                settings.setTransportChannelProvider(FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel)))
                        .setCredentialsProvider(NoCredentialsProvider.create());
            } else {
                credentialsProvider().ifPresent(settings::setCredentialsProvider);
            }
            return SecretManagerServiceClient.create(settings.build());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the Secret Manager client", e);
        }
    }

    /**
     * Password auth or Cloud SQL IAM, over a direct TLS connection to the host, or through the Cloud SQL
     * connector when {@code gcp.sql.instance} is set.
     */
    @Override
    public synchronized Database database(SqlConfig sql) {
        if (databases == null) {
            databases = new JdbcDatabases();
        }
        Optional<String> instance = sql.property(GcpProperties.SQL_INSTANCE);
        boolean iam = sql.auth() instanceof SqlAuth.Iam;
        checkIam(sql);
        String url = instance.map(i -> CloudSqlConnector.jdbcUrl(sql.engine(), sql.database())).orElseGet(sql::jdbcUrl);
        SqlCredentialsSource credentials = switch (sql.auth()) {
            case SqlAuth.Password password -> JdbcDatabases.passwordSource(password);
            case SqlAuth.Iam i when instance.isPresent() -> {
                // The connector fetches and sends the token; the driver only needs the user.
                CloudSqlIamTokens tokens = new CloudSqlIamTokens(googleCredentials(), sql.engine(), i.username());
                String user = tokens.username();
                yield () -> SqlLogin.password(user, null);
            }
            case SqlAuth.Iam i -> new CloudSqlIamTokens(googleCredentials(), sql.engine(), i.username());
        };
        Map<String, String> defaults = instance
                .map(i -> connectorProperties(sql.engine(), i, iam))
                .orElseGet(() -> iam ? JdbcDatabases.requireTls(sql.engine()) : Map.of());
        return databases.create(sql, url, credentials, defaults);
    }

    /** The connector fetches its own certificates and tokens, so it is told which service account to impersonate. */
    private Map<String, String> connectorProperties(SqlEngine engine, String instance, boolean iam) {
        Map<String, String> properties = new LinkedHashMap<>(CloudSqlConnector.properties(engine, instance, iam));
        if (config.credentials() instanceof Credentials.Impersonation impersonation) {
            properties.put("cloudSqlTargetPrincipal", impersonation.target());
        }
        return properties;
    }

    @Override
    public SqlEndpoint sqlEndpoint(SqlConfig sql) {
        if (sql.property(GcpProperties.SQL_INSTANCE).isPresent()) {
            throw new UnsupportedFeatureException("The Cloud SQL connector connects through its own socket factory, "
                    + "so it has no endpoint to hand out; connect to the instance's IP address instead",
                    null, GcpProvider.ID, null);
        }
        String host = sql.host().orElseThrow(() -> new IllegalArgumentException(
                "Cloud SQL needs a host, or " + GcpProperties.SQL_INSTANCE + " for the Cloud SQL connector"));
        checkIam(sql);
        return switch (sql.auth()) {
            case SqlAuth.Password password -> new SqlEndpoint(sql.engine(), host, sql.port(), sql.database(),
                    JdbcDatabases.passwordSource(password), false);
            case SqlAuth.Iam iam -> new SqlEndpoint(sql.engine(), host, sql.port(), sql.database(),
                    new CloudSqlIamTokens(googleCredentials(), sql.engine(), iam.username()), true);
        };
    }

    /** Cloud SQL IAM login needs PostgreSQL or MySQL, and a Google identity. */
    private void checkIam(SqlConfig sql) {
        if (!(sql.auth() instanceof SqlAuth.Iam)) {
            return;
        }
        if (sql.engine() == SqlEngine.SQLSERVER) {
            throw new UnsupportedFeatureException("Cloud SQL IAM authentication supports PostgreSQL and MySQL, "
                    + "not SQL Server; use password auth", null, GcpProvider.ID, null);
        }
        if (!GoogleIdentity.isIdentity(config.credentials())) {
            throw new IllegalArgumentException("Cloud SQL IAM authentication needs a Google identity: configure the "
                    + "provider with Credentials.defaultChain() or impersonation");
        }
    }

    /** A Managed Service for Apache Kafka topic: Apache Kafka, so idempotent and transactional producers are available. */
    private static final Capabilities MANAGED_KAFKA_CAPABILITIES = Capabilities.of(StreamCapability.values());

    @Override
    public synchronized ProducerDriver streamProducer(StreamProducerConfig producer) {
        return streams().producer(producer, managedKafkaCredentials(producer.cluster()), MANAGED_KAFKA_CAPABILITIES, Map.of());
    }

    @Override
    public synchronized ConsumerDriver streamConsumer(StreamConsumerConfig consumer) {
        return streams().consumer(consumer, managedKafkaCredentials(consumer.cluster()), MANAGED_KAFKA_CAPABILITIES, Map.of());
    }

    @Override
    public Map<String, Object> kafkaClientProperties(StreamConfig cluster) {
        return KafkaStreamClients.clientProperties(cluster, managedKafkaCredentials(cluster), Map.of());
    }

    private KafkaStreamClients streams() {
        if (streams == null) {
            streams = new KafkaStreamClients(GcpProvider.ID);
        }
        return streams;
    }

    /** Google IAM tokens for the provider's identity. */
    private KafkaCredentialsSource managedKafkaCredentials(StreamConfig cluster) {
        return KafkaStreamClients.credentials(cluster.auth(), () -> {
            if (!GoogleIdentity.isIdentity(config.credentials())) {
                throw new IllegalArgumentException("Managed Kafka IAM login needs a Google identity: configure the "
                        + "provider with Credentials.defaultChain() or impersonation");
            }
            return new ManagedKafkaTokens(googleCredentials(), cluster.property(GcpProperties.KAFKA_PRINCIPAL));
        });
    }

    private MemorystoreIamTokens iamTokens(CacheAuth.Iam iam) {
        if (!GoogleIdentity.isIdentity(config.credentials())) {
            throw new IllegalArgumentException("Memorystore IAM authentication needs a Google identity: configure the "
                    + "provider with Credentials.defaultChain() or impersonation");
        }
        return new MemorystoreIamTokens(googleCredentials(), iam.username());
    }

    /**
     * The provider's Google credentials, made once and shared by every client and token source: Application
     * Default Credentials, or a service account they impersonate.
     */
    private synchronized GoogleCredentials googleCredentials() {
        if (googleCredentials == null) {
            googleCredentials = GoogleIdentity.credentials(config.credentials());
        }
        return googleCredentials;
    }

    /** gRPC credentials when they differ from the client's own Application Default Credentials. */
    private Optional<com.google.api.gax.core.CredentialsProvider> credentialsProvider() {
        return switch (config.credentials()) {
            case Credentials.Anonymous ignored -> Optional.of(NoCredentialsProvider.create());
            case Credentials.DefaultChain ignored -> Optional.empty();
            default -> Optional.of(FixedCredentialsProvider.create(googleCredentials())); // refuses other kinds
        };
    }

    @Override
    public synchronized void close() {
        if (eventarc != null) {
            eventarc.close();
            eventarc = null;
        }
        if (streams != null) {
            streams.close();
            streams = null;
        }
        if (databases != null) {
            databases.close();
            databases = null;
        }
        if (firestore != null) {
            try {
                firestore.close();
            } catch (Exception ignored) {
                // closing anyway
            }
            firestore = null;
        }
        if (topicAdmin != null) {
            topicAdmin.close();
            topicAdmin = null;
        }
        if (subscriptionAdmin != null) {
            subscriptionAdmin.close();
            subscriptionAdmin = null;
        }
        if (caches != null) {
            caches.close();
            caches = null;
        }
        if (secretManager != null) {
            secretManager.close();
            secretManager = null;
        }
        parameterManagers.values().forEach(ParameterManagerClient::close);
        parameterManagers.clear();
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close GCS client", e);
            } finally {
                storage = null;
                shutdownTransport();
            }
        }
    }

    private void shutdownTransport() {
        try {
            httpTransport.shutdown();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close GCS HTTP transport", e);
        } finally {
            httpTransport = null;
        }
    }
}
