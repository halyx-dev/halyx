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
package org.kamranzafar.cirrus.gcp;

import com.google.api.gax.core.NoCredentialsProvider;
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
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.HttpSettings;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;
import org.kamranzafar.cirrus.jdbc.JdbcDatabases;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.nosql.TableConfig;
import org.kamranzafar.cirrus.nosql.spi.DocumentStoreDriver;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.messaging.spi.PublisherDriver;
import org.kamranzafar.cirrus.messaging.spi.SubscriptionDriver;
import org.kamranzafar.cirrus.cache.CacheAuth;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.cache.spi.CacheDriver;
import org.kamranzafar.cirrus.gcp.firestore.FirestoreDocumentStoreDriver;
import org.kamranzafar.cirrus.gcp.memorystore.MemorystoreIamTokens;
import org.kamranzafar.cirrus.gcp.parameters.ParameterManagerDriver;
import org.kamranzafar.cirrus.gcp.pubsub.PubSubPublisherDriver;
import org.kamranzafar.cirrus.gcp.pubsub.PubSubSubscriptionDriver;
import org.kamranzafar.cirrus.gcp.parameters.SecretManagerDriver;
import org.kamranzafar.cirrus.gcp.sql.CloudSqlConnector;
import org.kamranzafar.cirrus.gcp.sql.CloudSqlIamTokens;
import org.kamranzafar.cirrus.gcp.storage.GcsBlobStoreDriver;
import org.kamranzafar.cirrus.parameters.ParameterKind;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.parameters.spi.ParameterDriver;
import org.kamranzafar.cirrus.redis.RedisCaches;
import org.kamranzafar.cirrus.spi.ProviderConnection;
import org.kamranzafar.cirrus.sql.Database;
import org.kamranzafar.cirrus.sql.SqlAuth;
import org.kamranzafar.cirrus.sql.SqlConfig;
import org.kamranzafar.cirrus.sql.SqlEngine;
import org.kamranzafar.cirrus.sql.spi.SqlCredentialsSource;
import org.kamranzafar.cirrus.sql.spi.SqlLogin;
import org.kamranzafar.cirrus.storage.spi.BlobStoreDriver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
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
    private SecretManagerServiceClient secretManager;
    private final Map<String, ParameterManagerClient> parameterManagers = new HashMap<>();

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
                if (config.credentials() instanceof Credentials.Anonymous) {
                    settings.setCredentialsProvider(NoCredentialsProvider.create());
                }
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
            } else if (config.credentials() instanceof Credentials.Anonymous) {
                settings.setCredentialsProvider(NoCredentialsProvider.create());
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
            } else if (config.credentials() instanceof Credentials.Anonymous) {
                settings.setCredentialsProvider(NoCredentialsProvider.create());
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
        if (iam && sql.engine() == SqlEngine.SQLSERVER) {
            throw new UnsupportedFeatureException("Cloud SQL IAM authentication supports PostgreSQL and MySQL, "
                    + "not SQL Server; use password auth", null, GcpProvider.ID, null);
        }
        if (iam && !(config.credentials() instanceof Credentials.DefaultChain)) {
            throw new IllegalArgumentException("Cloud SQL IAM authentication uses Application Default Credentials; "
                    + "configure the provider with Credentials.defaultChain()");
        }
        String url = instance.map(i -> CloudSqlConnector.jdbcUrl(sql.engine(), sql.database())).orElseGet(sql::jdbcUrl);
        SqlCredentialsSource credentials = switch (sql.auth()) {
            case SqlAuth.Password password -> JdbcDatabases.passwordSource(password);
            case SqlAuth.Iam i when instance.isPresent() -> {
                // The connector fetches and sends the token; the driver only needs the user.
                CloudSqlIamTokens tokens = CloudSqlIamTokens.applicationDefault(sql.engine(), i.username());
                String user = tokens.username();
                yield () -> SqlLogin.password(user, null);
            }
            case SqlAuth.Iam i -> CloudSqlIamTokens.applicationDefault(sql.engine(), i.username());
        };
        Map<String, String> defaults = instance
                .map(i -> CloudSqlConnector.properties(sql.engine(), i, iam))
                .orElseGet(() -> iam ? JdbcDatabases.requireTls(sql.engine()) : Map.of());
        return databases.create(sql, url, credentials, defaults);
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
