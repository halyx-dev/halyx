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
package org.kamranzafar.cirrus.redis;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.SslOptions;
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
import io.netty.resolver.DefaultAddressResolverGroup;
import org.kamranzafar.cirrus.cache.CacheAuth;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.cache.spi.CacheDriver;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Opens and owns Redis/Valkey connections for one provider connection: one Lettuce client and
 * connection per distinct endpoint and auth, shared by every cache namespace on it. Providers call
 * {@link #driver} from {@code ProviderConnection.cache} and close this with the connection.
 *
 * <p>Cache properties understood here:
 * <ul>
 *   <li>{@code redis.command-timeout}: ISO-8601 duration, default {@code PT2S}. Keep it short: a cache
 *       that answers slowly should fail fast (and, with fail-open, degrade to a miss).</li>
 *   <li>{@code redis.ca-certificate}: path to a PEM file of CA certificates to trust for TLS, for
 *       servers whose certificates are not signed by a public CA, such as Memorystore's
 *       in-transit encryption.</li>
 *   <li>{@code redis.verify-peer}: {@code true} (default) checks the server certificate's chain and that it
 *       names the host connected to; {@code ca} checks the chain only, for a tunnel or proxy whose local
 *       address the certificate can't name; {@code false} skips TLS certificate checks, for local tests
 *       only.</li>
 *   <li>{@code redis.dns-resolver}: {@code netty} (default) resolves host names with Netty's asynchronous
 *       DNS client, which reads {@code /etc/hosts} and the system's DNS servers itself; {@code jdk}
 *       resolves them like the rest of the JVM ({@code InetAddress}), so JVM DNS settings apply: the
 *       {@code jdk.net.hosts.file} hosts file, DNS cache TTLs and custom {@code InetAddressResolver}s.
 *       Lookups then block a Netty thread briefly, on connect and reconnect only.</li>
 * </ul>
 */
public final class RedisCaches implements AutoCloseable {

    public static final String COMMAND_TIMEOUT = "redis.command-timeout";
    public static final String VERIFY_PEER = "redis.verify-peer";
    public static final String CA_CERTIFICATE = "redis.ca-certificate";
    public static final String DNS_RESOLVER = "redis.dns-resolver";
    static final Duration DEFAULT_COMMAND_TIMEOUT = Duration.ofSeconds(2);

    private record Key(URI endpoint, boolean cluster, CacheAuth auth, Map<String, String> properties) {
    }

    private record Opened(CacheDriver driver, AutoCloseable connection, AutoCloseable client,
                          RefreshingCredentialsProvider credentials) {
    }

    /** One per endpoint and auth; connects lazily and keeps what it opened for closing. */
    private final class Endpoint {
        final LazyCacheDriver driver;
        volatile Opened opened;

        Endpoint(CacheConfig config, RedisCredentialsSource credentials) {
            this.driver = new LazyCacheDriver(provider, () -> {
                if (closed) {
                    throw new IllegalStateException("Redis connections of provider '" + provider + "' are closed");
                }
                Opened o = connect(config, credentials);
                opened = o;
                return o.driver();
            });
        }
    }

    private final String provider;
    private final ClientResources resources = DefaultClientResources.create();
    /** Created on first use by a cache with {@code redis.dns-resolver=jdk}. */
    private ClientResources jdkResolverResources;
    private final Map<Key, Endpoint> open = new ConcurrentHashMap<>();
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

    /**
     * The driver for {@code config}'s server. It connects on first use and retries a failed connect
     * on later calls, so creating a cache never fails because the server is down.
     */
    public CacheDriver driver(CacheConfig config, RedisCredentialsSource credentials) {
        if (closed) {
            throw new IllegalStateException("Redis connections of provider '" + provider + "' are closed");
        }
        Key key = new Key(config.endpoint(), config.cluster(), config.auth(), config.properties());
        return open.computeIfAbsent(key, k -> new Endpoint(config, credentials)).driver;
    }

    private static SslVerifyMode verifyMode(CacheConfig config) {
        String mode = config.property(VERIFY_PEER).orElse("true").trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "true", "full" -> SslVerifyMode.FULL;
            case "ca" -> SslVerifyMode.CA;
            case "false", "none" -> SslVerifyMode.NONE;
            default -> throw new IllegalArgumentException(VERIFY_PEER + " must be true, ca or false, not '" + mode + "'");
        };
    }

    private ClientResources resources(CacheConfig config) {
        String resolver = config.property(DNS_RESOLVER).orElse("netty").trim().toLowerCase(Locale.ROOT);
        return switch (resolver) {
            case "netty" -> resources;
            case "jdk" -> {
                synchronized (this) {
                    if (jdkResolverResources == null) {
                        jdkResolverResources = DefaultClientResources.builder()
                                .addressResolverGroup(DefaultAddressResolverGroup.INSTANCE)
                                .build();
                    }
                    yield jdkResolverResources;
                }
            }
            default -> throw new IllegalArgumentException(DNS_RESOLVER + " must be netty or jdk, not '" + resolver + "'");
        };
    }

    private Opened connect(CacheConfig config, RedisCredentialsSource source) {
        Duration timeout = config.property(COMMAND_TIMEOUT).map(Duration::parse).orElse(DEFAULT_COMMAND_TIMEOUT);
        SslOptions ssl = config.property(CA_CERTIFICATE)
                .map(path -> SslOptions.builder().jdkSslProvider().trustManager(new File(path)).build())
                .orElseGet(SslOptions::create);
        URI endpoint = config.endpoint();
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(endpoint.getHost())
                .withPort(endpoint.getPort() > 0 ? endpoint.getPort() : RedisURI.DEFAULT_REDIS_PORT)
                .withTimeout(timeout)
                .withSsl(config.tls());
        if (config.tls()) {
            uri.withVerifyPeer(verifyMode(config));
        }
        RefreshingCredentialsProvider credentials = source == null ? null : new RefreshingCredentialsProvider(source);
        if (credentials != null) {
            uri.withAuthentication(credentials);
        }
        RedisURI redisUri = uri.build();
        TimeoutOptions timeouts = TimeoutOptions.enabled(timeout);
        ClientResources resources = resources(config);
        try {
            if (config.cluster()) {
                RedisClusterClient client = RedisClusterClient.create(resources, redisUri);
                client.setOptions(ClusterClientOptions.builder()
                        .timeoutOptions(timeouts)
                        .sslOptions(ssl)
                        .reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS)
                        // Managed clusters reshard and fail over: follow topology changes.
                        .topologyRefreshOptions(ClusterTopologyRefreshOptions.builder()
                                .enablePeriodicRefresh(Duration.ofSeconds(60))
                                .enableAllAdaptiveRefreshTriggers()
                                .build())
                        .build());
                StatefulRedisClusterConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
                RedisCacheDriver driver = new RedisCacheDriver(provider, connection.sync(), connection.sync(),
                        connection.async(), connection.sync(), timeout, List.of(connection, client));
                return new Opened(driver, connection, client::shutdown, credentials);
            }
            RedisClient client = RedisClient.create(resources, redisUri);
            client.setOptions(ClientOptions.builder()
                    .timeoutOptions(timeouts)
                    .sslOptions(ssl)
                    .reauthenticateBehavior(ClientOptions.ReauthenticateBehavior.ON_NEW_CREDENTIALS)
                    .build());
            StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);
            RedisCacheDriver driver = new RedisCacheDriver(provider, connection.sync(), connection.sync(),
                    connection.async(), connection.sync(), timeout, List.of(connection, client));
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
        for (Endpoint endpoint : open.values()) {
            Opened opened = endpoint.opened;
            if (opened == null) {
                continue;
            }
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
        synchronized (this) {
            if (jdkResolverResources != null) {
                jdkResolverResources.shutdown();
            }
        }
        if (!failures.isEmpty()) {
            throw failures.getFirst();
        }
    }
}
