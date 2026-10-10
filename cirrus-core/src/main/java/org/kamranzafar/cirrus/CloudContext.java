package org.kamranzafar.cirrus;

import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.cache.Codec;
import org.kamranzafar.cirrus.cache.DefaultCache;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.parameters.DefaultParameterStore;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.interceptor.InterceptorChain;
import org.kamranzafar.cirrus.messaging.DefaultPublisher;
import org.kamranzafar.cirrus.messaging.DefaultSubscription;
import org.kamranzafar.cirrus.messaging.Publisher;
import org.kamranzafar.cirrus.messaging.Subscription;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.spi.CloudProvider;
import org.kamranzafar.cirrus.spi.ProviderConnection;
import org.kamranzafar.cirrus.sql.Database;
import org.kamranzafar.cirrus.sql.SqlConfig;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.storage.DefaultBlobStore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One named, configured provider registration, such as {@code "orders"} on AWS eu-west-2. It hands
 * out portable service clients that share one underlying connection.
 *
 * <p>The connection opens lazily on first use. Clients are cached and thread-safe.
 */
public final class CloudContext implements AutoCloseable {

    private final String name;
    private final ProviderConfig config;
    private final CloudProvider provider;
    private final InterceptorChain interceptors;
    private final Map<String, BlobStore> blobStores = new ConcurrentHashMap<>();
    private volatile ProviderConnection connection;
    private volatile boolean closed;

    CloudContext(String name, ProviderConfig config, CloudProvider provider, InterceptorChain interceptors) {
        this.name = name;
        this.config = config;
        this.provider = provider;
        this.interceptors = interceptors;
    }

    public String name() {
        return name;
    }

    public String providerId() {
        return provider.id();
    }

    public ProviderConfig config() {
        return config;
    }

    /** The portable blob store for {@code bucket}. The bucket must already exist. */
    public BlobStore blobStore(String bucket) {
        return blobStores.computeIfAbsent(bucket,
                b -> new DefaultBlobStore(provider.id(), b, connection().blobStore(b), interceptors));
    }

    /**
     * A portable cache over the Redis-compatible server described by {@code config}. Caches on the
     * same endpoint share one connection.
     */
    public <V> Cache<V> cache(CacheConfig config, Codec<V> codec) {
        return new DefaultCache<>(provider.id(), config, codec, connection().cache(config), interceptors);
    }

    /** A cache of strings. */
    public Cache<String> cache(CacheConfig config) {
        return cache(config, Codec.string());
    }

    /** A portable parameter or secret store. */
    public ParameterStore parameterStore(ParameterStoreConfig config) {
        return new DefaultParameterStore(provider.id(), config, connection().parameterStore(config), interceptors);
    }

    /** A publisher to an existing topic or queue. Cheap to keep: create one per topic and share it. */
    public Publisher publisher(TopicConfig config) {
        return new DefaultPublisher(connection().publisher(config), config, interceptors);
    }

    public Publisher publisher(String topic) {
        return publisher(TopicConfig.of(topic));
    }

    /** A consumer of an existing subscription or queue: pull with {@code receive} or run {@code listen}. */
    public Subscription subscription(SubscriptionConfig config) {
        return new DefaultSubscription(connection().subscription(config), config, interceptors);
    }

    public Subscription subscription(String name) {
        return subscription(SubscriptionConfig.of(name));
    }

    /**
     * A pooled database. Each call creates a new pool, so create it once (per database) and share it;
     * it closes with this context.
     */
    public Database database(SqlConfig config) {
        return connection().database(config);
    }

    private ProviderConnection connection() {
        ProviderConnection c = connection;
        if (c == null) {
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("CloudContext '" + name + "' is closed");
                }
                c = connection;
                if (c == null) {
                    c = provider.connect(config);
                    connection = c;
                }
            }
        }
        return c;
    }

    @Override
    public synchronized void close() {
        closed = true;
        blobStores.clear();
        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    @Override
    public String toString() {
        return "CloudContext[name=" + name + ", provider=" + provider.id() + "]";
    }
}
