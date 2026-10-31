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
package dev.halyx;

import dev.halyx.cache.Cache;
import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.Codec;
import dev.halyx.cache.DefaultCache;
import dev.halyx.config.ProviderConfig;
import dev.halyx.nosql.DefaultDocumentStore;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.parameters.DefaultParameterStore;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.events.DefaultEventPublisher;
import dev.halyx.events.EventBusConfig;
import dev.halyx.events.EventPublisher;
import dev.halyx.functions.DefaultFunctionInvoker;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.messaging.DefaultPublisher;
import dev.halyx.messaging.DefaultSubscription;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.Subscription;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.spi.CloudProvider;
import dev.halyx.spi.ProviderConnection;
import dev.halyx.sql.Database;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEndpoint;
import dev.halyx.storage.BlobStore;
import dev.halyx.storage.DefaultBlobStore;
import dev.halyx.stream.DefaultStreamConsumer;
import dev.halyx.stream.DefaultStreamProducer;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducer;
import dev.halyx.stream.StreamProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One named, configured provider registration, such as {@code "orders"} on AWS eu-west-2. It hands
 * out portable service clients that share one underlying connection.
 *
 * <p>The connection opens lazily on first use. Clients are cached and thread-safe.
 */
public final class CloudContext implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CloudContext.class);

    private final String name;
    private final ProviderConfig config;
    private final CloudProvider provider;
    private final InterceptorChain interceptors;
    private final Map<String, BlobStore> blobStores = new ConcurrentHashMap<>();
    private final Map<CacheKey, Cache<?>> caches = new ConcurrentHashMap<>();
    private final Map<ParameterStoreConfig, ParameterStore> parameterStores = new ConcurrentHashMap<>();
    private final Map<TableConfig, DocumentStore> documentStores = new ConcurrentHashMap<>();
    private final Map<TopicConfig, Publisher> publishers = new ConcurrentHashMap<>();
    private final Map<SubscriptionConfig, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final Map<EventBusConfig, EventPublisher> eventPublishers = new ConcurrentHashMap<>();
    private final Map<FunctionConfig, FunctionInvoker> functionInvokers = new ConcurrentHashMap<>();
    private final Map<StreamProducerConfig, StreamProducer> streamProducers = new ConcurrentHashMap<>();
    /** Stream consumers created here, closed with this context; one running a listener is held by its poller. */
    private final Set<StreamConsumer> streamConsumers =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
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
     * same endpoint share one connection, and asking again with an equal configuration and the same codec
     * returns the same cache, with its statistics and circuit breaker.
     */
    @SuppressWarnings("unchecked")
    public <V> Cache<V> cache(CacheConfig config, Codec<V> codec) {
        return (Cache<V>) caches.computeIfAbsent(new CacheKey(config, codec),
                k -> new DefaultCache<>(provider.id(), config, codec, connection().cache(config), interceptors));
    }

    /** A cache of strings. */
    public Cache<String> cache(CacheConfig config) {
        return cache(config, Codec.string());
    }

    /**
     * A portable parameter or secret store. Asking again with an equal configuration returns the same store, so
     * its read cache is shared.
     */
    public ParameterStore parameterStore(ParameterStoreConfig config) {
        return parameterStores.computeIfAbsent(config,
                c -> new DefaultParameterStore(provider.id(), c, connection().parameterStore(c), interceptors));
    }

    /**
     * A portable document store over an existing table, container or collection. Asking again with an equal
     * configuration returns the same store.
     */
    public DocumentStore documentStore(TableConfig config) {
        return documentStores.computeIfAbsent(config,
                c -> new DefaultDocumentStore(connection().documentStore(c), interceptors));
    }

    /**
     * A publisher to an existing topic or queue. Asking again with an equal configuration returns the same
     * publisher.
     */
    public Publisher publisher(TopicConfig config) {
        return publishers.computeIfAbsent(config, c -> new DefaultPublisher(connection().publisher(c), c, interceptors));
    }

    public Publisher publisher(String topic) {
        return publisher(TopicConfig.of(topic));
    }

    /**
     * A consumer of an existing subscription or queue: pull with {@code receive} or run {@code listen}. Asking
     * again with an equal configuration returns the same subscription.
     */
    public Subscription subscription(SubscriptionConfig config) {
        return subscriptions.computeIfAbsent(config,
                c -> new DefaultSubscription(connection().subscription(c), c, interceptors));
    }

    public Subscription subscription(String name) {
        return subscription(SubscriptionConfig.of(name));
    }

    /**
     * A publisher of CloudEvents to an event bus: an EventBridge event bus, an Event Grid namespace topic, or an
     * Eventarc Advanced message bus. Receive the events from the queue the router delivers them to. Asking again
     * with an equal configuration returns the same publisher.
     */
    public EventPublisher eventPublisher(EventBusConfig config) {
        return eventPublishers.computeIfAbsent(config,
                c -> new DefaultEventPublisher(connection().eventPublisher(c), interceptors));
    }

    public EventPublisher eventPublisher(String bus) {
        return eventPublisher(EventBusConfig.of(bus));
    }

    /**
     * An invoker of a function: an AWS Lambda function, an Azure function or a Cloud Run function. Asking again with
     * an equal configuration returns the same invoker.
     */
    public FunctionInvoker functionInvoker(FunctionConfig config) {
        return functionInvokers.computeIfAbsent(config,
                c -> new DefaultFunctionInvoker(connection().functionInvoker(c), c, interceptors));
    }

    public FunctionInvoker functionInvoker(String function) {
        return functionInvoker(FunctionConfig.of(function));
    }

    /**
     * A producer for an existing stream topic: Amazon MSK, Azure Event Hubs or Google Cloud Managed Service for
     * Apache Kafka. Thread-safe: asking again with an equal configuration returns the same producer.
     */
    public StreamProducer streamProducer(StreamProducerConfig config) {
        return streamProducers.computeIfAbsent(config,
                c -> new DefaultStreamProducer(connection().streamProducer(c), c, interceptors));
    }

    /**
     * A consumer group member on an existing stream topic: read with {@code poll} or run {@code listen}. Each call
     * creates a new member; close it to leave the group.
     */
    public StreamConsumer streamConsumer(StreamConsumerConfig config) {
        StreamConsumer consumer = new DefaultStreamConsumer(connection().streamConsumer(config), config, interceptors);
        streamConsumers.add(consumer);
        return consumer;
    }

    /**
     * Where and how this provider connects to a database, for clients other than {@link #database}'s JDBC pool,
     * such as R2DBC: the host, the login for each new connection (a password, or a cloud IAM token) and whether
     * TLS is required. Needs a host: the Cloud SQL connector connects its own way.
     */
    public SqlEndpoint sqlEndpoint(SqlConfig config) {
        return connection().sqlEndpoint(config);
    }

    /**
     * Kafka client properties that connect and log in to a stream cluster the way this provider's producers and
     * consumers do: bootstrap servers, TLS and SASL with the cloud login (MSK IAM, Entra ID or Google IAM tokens,
     * refreshed before they expire), the provider's client defaults and the cluster's {@code kafka.*} properties.
     * For Kafka's own clients and Spring for Apache Kafka. Some values are objects, not strings, so pass the map
     * to the clients as it is.
     */
    public Map<String, Object> kafkaClientProperties(StreamConfig cluster) {
        return connection().kafkaClientProperties(cluster);
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

    /**
     * Stops the listeners and stream consumers started from this context, letting running handlers finish, then
     * closes its provider connection and every client it opened.
     */
    @Override
    public void close() {
        ProviderConnection open;
        synchronized (this) {
            closed = true;
            open = connection;
            connection = null;
        }
        stopConsumers();
        // Outside the lock: a client being created holds its map entry while it waits for the lock in connection().
        blobStores.clear();
        caches.clear();
        parameterStores.clear();
        documentStores.clear();
        publishers.clear();
        subscriptions.clear();
        eventPublishers.clear();
        functionInvokers.clear();
        streamProducers.clear();
        if (open != null) {
            open.close();
        }
    }

    /**
     * Stops stream consumers and message listeners before the clients they use close, so their running handlers
     * finish and what they handled is committed or acked. They stop in parallel, each within its own shutdown
     * timeout.
     */
    private void stopConsumers() {
        List<AutoCloseable> running = new ArrayList<>();
        synchronized (streamConsumers) {
            running.addAll(streamConsumers);
            streamConsumers.clear();
        }
        for (Subscription subscription : subscriptions.values()) {
            if (subscription instanceof DefaultSubscription portable) {
                running.add(new AutoCloseable() {
                    @Override
                    public void close() {
                        portable.closeConsumers();
                    }

                    @Override
                    public String toString() {
                        return "the listeners of " + portable;
                    }
                });
            }
        }
        try (ExecutorService stopping = Executors.newVirtualThreadPerTaskExecutor()) {
            for (AutoCloseable consumer : running) {
                stopping.execute(() -> {
                    try {
                        consumer.close();
                    } catch (Exception e) {
                        LOG.warn("Could not stop {} cleanly", consumer, e);
                    }
                });
            }
        }
    }

    private record CacheKey(CacheConfig config, Codec<?> codec) {
    }

    @Override
    public String toString() {
        return "CloudContext[name=" + name + ", provider=" + provider.id() + "]";
    }
}
