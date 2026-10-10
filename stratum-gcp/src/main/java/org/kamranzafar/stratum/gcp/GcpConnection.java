package org.kamranzafar.stratum.gcp;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.NoCredentials;
import com.google.cloud.ServiceOptions;
import com.google.cloud.parametermanager.v1.ParameterManagerClient;
import com.google.cloud.parametermanager.v1.ParameterManagerSettings;
import com.google.cloud.secretmanager.v1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1.SecretManagerServiceSettings;
import io.grpc.ManagedChannelBuilder;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.cache.CacheAuth;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.cache.spi.CacheDriver;
import org.kamranzafar.stratum.gcp.memorystore.MemorystoreIamTokens;
import org.kamranzafar.stratum.gcp.parameters.ParameterManagerDriver;
import org.kamranzafar.stratum.gcp.parameters.SecretManagerDriver;
import org.kamranzafar.stratum.gcp.storage.GcsBlobStoreDriver;
import org.kamranzafar.stratum.parameters.ParameterKind;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.kamranzafar.stratum.parameters.spi.ParameterDriver;
import org.kamranzafar.stratum.redis.RedisCaches;
import org.kamranzafar.stratum.spi.ProviderConnection;
import org.kamranzafar.stratum.storage.spi.BlobStoreDriver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/** Owns the Google Cloud clients for one configuration. Clients are created lazily and shared by all buckets. */
final class GcpConnection implements ProviderConnection {

    private final ProviderConfig config;
    private Storage storage;
    private RedisCaches caches;
    private SecretManagerServiceClient secretManager;
    private final Map<String, ParameterManagerClient> parameterManagers = new HashMap<>();

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
            }
        }
    }
}
