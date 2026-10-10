package org.kamranzafar.stratum.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.kamranzafar.stratum.cache.CacheAuth;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.cache.spi.CacheDriver;
import org.kamranzafar.stratum.error.UnsupportedFeatureException;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Opens and owns Redis/Valkey connections for one provider connection: one Lettuce client and
 * connection per distinct endpoint and auth, shared by every cache namespace on it. Providers call
 * {@link #driver} from {@code ProviderConnection.cache} and close this with the connection.
 *
 * <p>Cache properties understood here: {@code redis.command-timeout} (ISO-8601 duration, default
 * {@code PT10S}) and {@code redis.verify-peer} ({@code false} to skip TLS certificate checks, for
 * local tunnels only).
 */
public final class RedisCaches implements AutoCloseable {

    public static final String COMMAND_TIMEOUT = "redis.command-timeout";
    public static final String VERIFY_PEER = "redis.verify-peer";

    private record Key(URI endpoint, boolean cluster, CacheAuth auth, Map<String, String> properties) {
    }

    private record Opened(CacheDriver driver, AutoCloseable connection, AutoCloseable client,
                          RefreshingCredentialsProvider credentials) {
    }

    private final String provider;
    private final ClientResources resources = DefaultClientResources.create();
    private final Map<Key, Opened> open = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public RedisCaches(String provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * Maps a {@link CacheAuth} to a credentials source: none, a fixed password, or the provider's IAM
     * token source.
     *
     * @return null for {@link CacheAuth.None}
     */
    public static RedisCredentialsSource credentials(CacheAuth auth,
                                                     Function<CacheAuth.Iam, RedisCredentialsSource> iamTokens) {
        return switch (auth) {
            case CacheAuth.None ignored -> null;
            case CacheAuth.Password p -> {
                RedisLogin login = RedisLogin.of(p.username().orElse(null), p.password());
                yield () -> login;
            }
            case CacheAuth.Iam iam -> iamTokens.apply(iam);
        };
    }

    /** IAM token source for providers that do not offer one. */
    public static Function<CacheAuth.Iam, RedisCredentialsSource> iamUnsupported(String provider) {
        return iam -> {
            throw new UnsupportedFeatureException("IAM cache authentication is not supported by " + provider,
                    null, provider, null);
        };
    }

    /** The driver for {@code config}'s server, connecting on first use. */
    public CacheDriver driver(CacheConfig config, RedisCredentialsSource credentials) {
        if (closed) {
            throw new IllegalStateException("Redis connections of provider '" + provider + "' are closed");
        }
        Key key = new Key(config.endpoint(), config.cluster(), config.auth(), config.properties());
        return open.computeIfAbsent(key, k -> connect(config, credentials)).driver();
    }

    private Opened connect(CacheConfig config, RedisCredentialsSource source) {
        Duration timeout = config.property(COMMAND_TIMEOUT).map(Duration::parse).orElse(Duration.ofSeconds(10));
        URI endpoint = config.endpoint();
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(endpoint.getHost())
                .withPort(endpoint.getPort() > 0 ? endpoint.getPort() : RedisURI.DEFAULT_REDIS_PORT)
                .withTimeout(timeout)
                .withSsl(config.tls());
        if (config.tls() && "false".equalsIgnoreCase(config.property(VERIFY_PEER).orElse("true"))) {
            uri.withVerifyPeer(SslVerifyMode.NONE);
        }
        RefreshingCredentialsProvider credentials = source == null ? null : new RefreshingCredentialsProvider(source);
        if (credentials != null) {
            uri.withAuthentication(credentials);
        }
        RedisURI redisUri = uri.build();
        TimeoutOptions timeouts = TimeoutOptions.enabled(timeout);
        try {
            if (config.cluster()) {
                RedisClusterClient client = RedisClusterClient.create(resources, redisUri);
                client.setOptions(ClusterClientOptions.builder()
                        .timeoutOptions(timeouts)
                        .reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS)
                        // Managed clusters reshard and fail over: follow topology changes.
                        .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                                .enablePeriodicRefresh(Duration.ofSeconds(60))
                                .enableAllAdaptiveRefreshTriggers()
                                .build())
                        .build());
                StatefulRedisClusterConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
                RedisCacheDriver driver = new RedisCacheDriver(provider, connection.sync(), connection.sync(),
                        connection.async(), timeout, List.of(connection, client));
                return new Opened(driver, connection, client::shutdown, credentials);
            }
            RedisClient client = RedisClient.create(resources, redisUri);
            client.setOptions(ClientOptions.builder()
                    .timeoutOptions(timeouts)
                    .reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS)
                    .build());
            StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
            RedisCacheDriver driver = new RedisCacheDriver(provider, connection.sync(), connection.sync(),
                    connection.async(), timeout, List.of(connection, client));
            return new Opened(driver, connection, client::shutdown, credentials);
        } catch (RuntimeException e) {
            if (credentials != null) {
                credentials.close();
            }
            throw RedisErrors.translate(e, provider).map(RuntimeException.class::cast).orElse(e);
        }
    }

    @Override
    public void close() {
        closed = true;
        List<RuntimeException> failures = new ArrayList<>();
        for (Opened opened : open.values()) {
            for (AutoCloseable resource : new AutoCloseable[] {opened.connection(), opened.client(), opened.credentials()}) {
                if (resource == null) {
                    continue;
                }
                try {
                    resource.close();
                } catch (Exception e) {
                    failures.add(new IllegalStateException("Failed to close Redis resource", e));
                }
            }
        }
        open.clear();
        resources.shutdown();
        if (!failures.isEmpty()) {
            throw failures.getFirst();
        }
    }
}
