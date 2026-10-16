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
package dev.halyx.spring.autoconfigure;

import dev.halyx.cache.CacheAuth;
import dev.halyx.cache.CacheConfig;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.interceptor.RetryPolicy;
import dev.halyx.messaging.ClaimCheck;
import dev.halyx.messaging.ConsumerOptions;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.nosql.TableConfig;
import dev.halyx.parameters.ParameterKind;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.sql.PoolSettings;
import dev.halyx.sql.SqlAuth;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEngine;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.ListenerOptions;
import dev.halyx.stream.SaslMechanism;
import dev.halyx.stream.StartPosition;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducer;
import dev.halyx.stream.StreamProducerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Halyx configuration:
 *
 * <pre>{@code
 * halyx:
 *   providers:
 *     orders:
 *       type: aws
 *       region: eu-west-2
 *   blob-stores:
 *     invoices:            # bean name
 *       provider: orders   # optional when only one provider is configured
 *       bucket: acme-invoices
 *   caches:
 *     sessions:            # bean name
 *       endpoint: rediss://master.sessions.abc123.euw2.cache.amazonaws.com:6379
 *       default-ttl: 30m
 *       auth: { type: iam, username: sessions-app }
 *       properties: { aws.elasticache.cache-name: sessions }
 * }</pre>
 *
 * @param providers   named provider connections
 * @param blobStores  {@code BlobStore} beans to create, keyed by bean name
 * @param caches      {@code Cache<String>} beans to create, keyed by bean name
 * @param cacheManager a Spring {@code CacheManager} for {@code @Cacheable}, enabled by setting its endpoint
 * @param parameterStores {@code ParameterStore} beans to create, keyed by bean name; also the names used by
 *                    {@code spring.config.import=halyx:<name>}
 * @param databases   pooled {@code DataSource} beans to create, keyed by bean name
 * @param documentStores {@code DocumentStore} beans to create, keyed by bean name
 * @param topics      {@code Publisher} beans to create, keyed by bean name
 * @param subscriptions {@code Subscription} beans to create, keyed by bean name; also what
 *                    {@code @HalyxListener} refers to
 * @param streamClusters stream clusters (MSK, Event Hubs, Managed Kafka) that producers and consumers use
 * @param streamProducers {@code StreamProducer} beans to create, keyed by bean name
 * @param streamConsumers {@code StreamConsumer} beans to create, keyed by bean name; {@code @HalyxListener}
 *                    can refer to them too
 * @param retry       provider-neutral retries on top of the SDKs' own
 * @param observations Micrometer observations of every operation
 */
@ConfigurationProperties("halyx")
public record HalyxProperties(Map<String, Provider> providers, Map<String, BlobStoreProperties> blobStores,
                                Map<String, CacheProperties> caches, @DefaultValue CacheManagerProperties cacheManager,
                                Map<String, ParameterStoreProperties> parameterStores,
                                Map<String, DatabaseProperties> databases,
                                Map<String, DocumentStoreProperties> documentStores,
                                Map<String, TopicProperties> topics, Map<String, SubscriptionProperties> subscriptions,
                                Map<String, StreamClusterProperties> streamClusters,
                                Map<String, StreamProducerProperties> streamProducers,
                                Map<String, StreamConsumerProperties> streamConsumers,
                                @DefaultValue Retry retry, @DefaultValue Observations observations) {

    public HalyxProperties {
        providers = ordered(providers);
        blobStores = ordered(blobStores);
        caches = ordered(caches);
        parameterStores = ordered(parameterStores);
        databases = ordered(databases);
        documentStores = ordered(documentStores);
        topics = ordered(topics);
        subscriptions = ordered(subscriptions);
        streamClusters = ordered(streamClusters);
        streamProducers = ordered(streamProducers);
        streamConsumers = ordered(streamConsumers);
    }

    private static <V> Map<String, V> ordered(Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    /**
     * One provider connection.
     *
     * @param type        provider id: {@code aws}, {@code azure}, {@code gcp} or {@code memory}. The
     *                    matching {@code halyx-<type>} module must be on the classpath
     * @param region      cloud region, where the provider uses one
     * @param endpoint    endpoint override, for emulators and private endpoints
     * @param credentials how to authenticate; the platform default chain when omitted
     * @param properties  provider-specific settings, such as {@code aws.s3.path-style} or
     *                    {@code gcp.project-id}
     */
    public record Provider(String type, String region, URI endpoint, @DefaultValue CredentialsProperties credentials,
                           Map<String, String> properties) {

        public Provider {
            properties = ordered(properties);
        }

        public ProviderConfig toProviderConfig(String name) {
            if (type == null || type.isBlank()) {
                throw new IllegalStateException("halyx.providers." + name + ".type is required (aws, azure, gcp or memory)");
            }
            ProviderConfig config = ProviderConfig.of(type).credentials(credentials.toCredentials(name));
            if (region != null) {
                config = config.region(region);
            }
            if (endpoint != null) {
                config = config.endpoint(endpoint);
            }
            for (var property : properties.entrySet()) {
                config = config.property(property.getKey(), property.getValue());
            }
            return config;
        }
    }

    /**
     * @param type   {@code default} (platform credential chain), {@code static} (id and secret, such as an
     *               AWS access key or an Azure account name and key) or {@code anonymous}
     */
    public record CredentialsProperties(@DefaultValue("default") Type type, String id, String secret) {

        public enum Type { DEFAULT, STATIC, ANONYMOUS }

        Credentials toCredentials(String provider) {
            return switch (type) {
                case DEFAULT -> Credentials.defaultChain();
                case ANONYMOUS -> Credentials.anonymous();
                case STATIC -> {
                    if (id == null || secret == null) {
                        throw new IllegalStateException("halyx.providers." + provider
                                + ".credentials: static credentials need both id and secret");
                    }
                    yield Credentials.of(id, secret);
                }
            };
        }
    }

    /**
     * @param provider the provider name; may be omitted when exactly one provider is configured
     * @param bucket   bucket or container name; defaults to the bean name
     */
    public record BlobStoreProperties(String provider, String bucket) {
    }

    /**
     * @param enabled        whether to retry throttled and transiently failing idempotent operations
     * @param maxAttempts    total attempts including the first
     * @param initialBackoff upper bound of the randomised delay before the first retry
     * @param maxBackoff     cap on the delay between attempts
     * @param multiplier     growth factor of the delay bound per attempt
     */
    /**
     * @param provider   the provider name; may be omitted when exactly one provider is configured
     * @param endpoint   {@code redis://host:port}, or {@code rediss://host:port} for TLS
     * @param namespace  key prefix ({@code namespace:key}); defaults to the bean name, empty for none
     * @param defaultTtl expiry for writes that do not give one; none when omitted
     * @param cluster    whether the server runs in cluster mode
     * @param failOpen   treat the cache as best effort when the server is unavailable (see {@code CacheConfig.failOpen})
     * @param auth       how to log in
     * @param properties provider-specific settings, such as {@code aws.elasticache.cache-name}
     */
    public record CacheProperties(String provider, URI endpoint, String namespace, Duration defaultTtl,
                                  @DefaultValue("false") boolean cluster, @DefaultValue("false") boolean failOpen,
                                  @DefaultValue CacheAuthProperties auth, Map<String, String> properties) {

        public CacheProperties {
            properties = ordered(properties);
        }

        CacheConfig toCacheConfig(String name) {
            if (endpoint == null) {
                throw new IllegalStateException("halyx.caches." + name + ".endpoint is required (redis:// or rediss://)");
            }
            CacheConfig.Builder config = CacheConfig.builder(endpoint)
                    .namespace(namespace == null ? name : namespace)
                    .cluster(cluster)
                    .failOpen(failOpen)
                    .auth(auth.toCacheAuth(name));
            if (defaultTtl != null) {
                config.defaultTtl(defaultTtl);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * @param type     {@code none}, {@code password} (AUTH token / access key, optionally with an ACL
     *                 username) or {@code iam} (cloud identity tokens: ElastiCache IAM, Entra ID, Memorystore IAM)
     * @param username ACL username; for {@code iam}, the ElastiCache user id or the Entra object id
     * @param password the password, for {@code password}
     */
    public record CacheAuthProperties(@DefaultValue("none") Type type, String username, String password) {

        public enum Type { NONE, PASSWORD, IAM }

        CacheAuth toCacheAuth(String cache) {
            return switch (type) {
                case NONE -> CacheAuth.none();
                case IAM -> username == null ? CacheAuth.iam() : CacheAuth.iam(username);
                case PASSWORD -> {
                    if (password == null) {
                        throw new IllegalStateException("halyx.caches." + cache + ".auth.password is required for password auth");
                    }
                    yield username == null ? CacheAuth.password(password) : CacheAuth.password(username, password);
                }
            };
        }
    }

    /**
     * A document table, registered as a {@code DocumentStore} bean named after its key.
     *
     * @param provider     the provider name; may be omitted when exactly one provider is configured
     * @param table        the DynamoDB table, Cosmos DB container or Firestore collection; defaults to the key
     * @param partitionKey partition key attribute (default {@code pk})
     * @param sortKey      sort key attribute, for tables that have one
     * @param indexes      secondary indexes by name
     * @param properties   provider-specific settings, such as {@code aws.dynamodb.ttl-attribute}
     */
    public record DocumentStoreProperties(String provider, String table, @DefaultValue("pk") String partitionKey,
                                          String sortKey, Map<String, IndexProperties> indexes,
                                          Map<String, String> properties) {

        public DocumentStoreProperties {
            indexes = ordered(indexes);
            properties = ordered(properties);
        }

        TableConfig toTableConfig(String key) {
            TableConfig.Builder config = TableConfig.builder(table == null || table.isBlank() ? key : table)
                    .partitionKey(partitionKey);
            if (sortKey != null && !sortKey.isBlank()) {
                config.sortKey(sortKey);
            }
            indexes.forEach((index, attributes) -> {
                if (attributes.partitionAttribute() == null) {
                    throw new IllegalStateException("halyx.document-stores." + key + ".indexes." + index
                            + ".partition-attribute is required");
                }
                if (attributes.sortAttribute() == null) {
                    config.index(index, attributes.partitionAttribute());
                } else {
                    config.index(index, attributes.partitionAttribute(), attributes.sortAttribute());
                }
            });
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * @param partitionAttribute the field queried for equality
     * @param sortAttribute      the string field results are ordered and narrowed by, if any
     */
    public record IndexProperties(String partitionAttribute, String sortAttribute) {
    }

    /**
     * A topic or queue to publish to, registered as a {@code Publisher} bean named after its key.
     *
     * @param provider   the provider name; may be omitted when exactly one provider is configured
     * @param name       the provider's topic name (SNS topic name or ARN, Service Bus topic, Pub/Sub topic id);
     *                   defaults to the key
     * @param claimCheck send large bodies through a blob store
     * @param properties provider-specific settings, such as {@code aws.target=sqs}
     */
    public record TopicProperties(String provider, String name, ClaimCheckProperties claimCheck,
                                  Map<String, String> properties) {

        public TopicProperties {
            properties = ordered(properties);
        }

        TopicConfig toTopicConfig(String key, ClaimCheck resolvedClaimCheck) {
            TopicConfig.Builder config = TopicConfig.builder(name == null || name.isBlank() ? key : name);
            if (resolvedClaimCheck != null) {
                config.claimCheck(resolvedClaimCheck);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * A subscription or queue to consume from, registered as a {@code Subscription} bean named after its key.
     *
     * @param provider   the provider name; may be omitted when exactly one provider is configured
     * @param name       the provider's subscription name (SQS queue, {@code <topic>/subscriptions/<name>} on
     *                   Service Bus, Pub/Sub subscription id); defaults to the key
     * @param topic      the topic, for providers that bind subscriptions themselves (memory)
     * @param claimCheck resolve bodies sent through a blob store; must match the publisher's
     * @param listener   how {@code @HalyxListener} methods on this subscription are run
     * @param properties provider-specific settings
     */
    public record SubscriptionProperties(String provider, String name, String topic, ClaimCheckProperties claimCheck,
                                         @DefaultValue ListenerProperties listener, Map<String, String> properties) {

        public SubscriptionProperties {
            properties = ordered(properties);
        }

        SubscriptionConfig toSubscriptionConfig(String key, ClaimCheck resolvedClaimCheck) {
            SubscriptionConfig.Builder config = SubscriptionConfig.builder(name == null || name.isBlank() ? key : name);
            if (topic != null) {
                config.topic(topic);
            }
            if (resolvedClaimCheck != null) {
                config.claimCheck(resolvedClaimCheck);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * @param blobStore bean name of one of the {@code halyx.blob-stores}
     * @param threshold bodies larger than this many bytes go through the store (default 204800)
     * @param prefix    key prefix of stored bodies (default {@code claim-check/})
     */
    public record ClaimCheckProperties(String blobStore, Integer threshold, String prefix) {

        ClaimCheck toClaimCheck(BlobStore store) {
            ClaimCheck defaults = ClaimCheck.of(store);
            return new ClaimCheck(store, threshold == null ? defaults.thresholdBytes() : threshold,
                    prefix == null ? defaults.prefix() : prefix);
        }
    }

    /** See {@code ConsumerOptions}; unset values keep its defaults. */
    public record ListenerProperties(Integer concurrency, Integer batchSize, Duration pollWait, Duration initialBackoff,
                                     Duration maxBackoff, Boolean extendLease, Duration shutdownTimeout) {

        public ConsumerOptions toConsumerOptions(int concurrencyOverride) {
            ConsumerOptions d = ConsumerOptions.DEFAULTS;
            int threads = concurrencyOverride > 0 ? concurrencyOverride : concurrency == null ? d.concurrency() : concurrency;
            return new ConsumerOptions(threads, batchSize == null ? d.batchSize() : batchSize,
                    pollWait == null ? d.pollWait() : pollWait,
                    initialBackoff == null ? d.initialBackoff() : initialBackoff,
                    maxBackoff == null ? d.maxBackoff() : maxBackoff,
                    extendLease == null ? d.extendLease() : extendLease,
                    shutdownTimeout == null ? d.shutdownTimeout() : shutdownTimeout);
        }
    }

    /**
     * A stream cluster: Amazon MSK, Azure Event Hubs (its Kafka endpoint) or Google Cloud Managed Service for
     * Apache Kafka.
     *
     * @param provider         the provider name; may be omitted when exactly one provider is configured
     * @param bootstrapServers comma-separated {@code host:port} pairs
     * @param auth             how clients log in
     * @param properties       provider settings, such as {@code aws.msk.region}, or Kafka client settings
     *                         prefixed with {@code kafka.}
     */
    public record StreamClusterProperties(String provider, String bootstrapServers,
                                          @DefaultValue StreamAuthProperties auth, Map<String, String> properties) {

        public StreamClusterProperties {
            properties = ordered(properties);
        }

        StreamConfig toStreamConfig(String key) {
            String prefix = "halyx.stream-clusters." + key;
            if (bootstrapServers == null || bootstrapServers.isBlank()) {
                throw new IllegalStateException(prefix + ".bootstrap-servers is required");
            }
            StreamConfig.Builder config = StreamConfig.builder(bootstrapServers).auth(auth.toStreamAuth(prefix + ".auth"));
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * @param type      {@code none}, {@code password} or {@code iam} (MSK IAM, Entra ID, Google IAM tokens)
     * @param mechanism for {@code password}: {@code plain}, {@code scram-sha-256} or {@code scram-sha-512}
     * @param username  for {@code password}; {@code $ConnectionString} for an Event Hubs connection string
     * @param password  for {@code password}
     */
    public record StreamAuthProperties(@DefaultValue("none") Type type, SaslMechanism mechanism, String username,
                                       String password) {

        public enum Type { NONE, PASSWORD, IAM }

        StreamAuth toStreamAuth(String prefix) {
            return switch (type) {
                case NONE -> StreamAuth.none();
                case IAM -> StreamAuth.iam();
                case PASSWORD -> {
                    if (mechanism == null || username == null || password == null) {
                        throw new IllegalStateException(prefix + ".mechanism, .username and .password are required "
                                + "for password auth");
                    }
                    yield StreamAuth.password(mechanism, username, password);
                }
            };
        }
    }

    /**
     * A stream topic to send to, registered as a {@code StreamProducer} bean named after its key.
     *
     * @param cluster       one of the {@code halyx.stream-clusters}; may be omitted when exactly one is configured
     * @param topic         the topic; defaults to the key
     * @param claimCheck    send large values through a blob store
     * @param idempotent    have the broker drop resent duplicates; needs the {@code IDEMPOTENCE} capability
     * @param transactional make {@code sendAll} all or nothing; needs the {@code TRANSACTIONS} capability
     * @param properties    provider settings, or Kafka producer settings prefixed with {@code kafka.}
     */
    public record StreamProducerProperties(String cluster, String topic, ClaimCheckProperties claimCheck,
                                           @DefaultValue("false") boolean idempotent,
                                           @DefaultValue("false") boolean transactional, Map<String, String> properties) {

        public StreamProducerProperties {
            properties = ordered(properties);
        }

        StreamProducerConfig toProducerConfig(String key, StreamConfig resolvedCluster, ClaimCheck resolvedClaimCheck) {
            StreamProducerConfig.Builder config = StreamProducerConfig.builder(resolvedCluster,
                    topic == null || topic.isBlank() ? key : topic).idempotent(idempotent).transactional(transactional);
            if (resolvedClaimCheck != null) {
                config.claimCheck(resolvedClaimCheck);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * A consumer group member on a stream topic, registered as a {@code StreamConsumer} bean named after its key.
     *
     * @param cluster        one of the {@code halyx.stream-clusters}; may be omitted when exactly one is configured
     * @param topic          the topic; defaults to the key
     * @param group          the consumer group; defaults to the key
     * @param startPosition  {@code earliest} or {@code latest} (default), where a new group starts
     * @param readCommitted  hide records of aborted and open transactions
     * @param claimCheck     resolve values sent through a blob store; must match the producer's
     * @param listener       how {@code @HalyxListener} methods on this consumer are run
     * @param properties     provider settings, or Kafka consumer settings prefixed with {@code kafka.}
     */
    public record StreamConsumerProperties(String cluster, String topic, String group,
                                           @DefaultValue("latest") StartPosition startPosition,
                                           @DefaultValue("false") boolean readCommitted, ClaimCheckProperties claimCheck,
                                           @DefaultValue StreamListenerProperties listener,
                                           Map<String, String> properties) {

        public StreamConsumerProperties {
            properties = ordered(properties);
        }

        StreamConsumerConfig toConsumerConfig(String key, StreamConfig resolvedCluster, ClaimCheck resolvedClaimCheck) {
            StreamConsumerConfig.Builder config = StreamConsumerConfig.builder(resolvedCluster,
                            topic == null || topic.isBlank() ? key : topic, group == null || group.isBlank() ? key : group)
                    .startPosition(startPosition).readCommitted(readCommitted);
            if (resolvedClaimCheck != null) {
                config.claimCheck(resolvedClaimCheck);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * See {@code ListenerOptions}; unset values keep its defaults.
     *
     * @param deadLetter bean name of one of the {@code halyx.stream-producers}, where records go after
     *                   {@code max-attempts}
     */
    public record StreamListenerProperties(Duration pollWait, Integer maxBufferedRecords, Duration initialBackoff,
                                           Duration maxBackoff, Integer maxAttempts, String deadLetter,
                                           Duration commitInterval, Duration shutdownTimeout) {

        public ListenerOptions toListenerOptions(StreamProducer resolvedDeadLetter) {
            ListenerOptions d = ListenerOptions.DEFAULTS;
            return new ListenerOptions(pollWait == null ? d.pollWait() : pollWait,
                    maxBufferedRecords == null ? d.maxBufferedRecords() : maxBufferedRecords,
                    initialBackoff == null ? d.initialBackoff() : initialBackoff,
                    maxBackoff == null ? d.maxBackoff() : maxBackoff,
                    maxAttempts == null ? d.maxAttempts() : maxAttempts, resolvedDeadLetter,
                    commitInterval == null ? d.commitInterval() : commitInterval,
                    shutdownTimeout == null ? d.shutdownTimeout() : shutdownTimeout);
        }
    }

    /**
     * A pooled database, registered as a {@code DataSource} bean named after its key. A single database is
     * the primary {@code DataSource}, so JPA, JDBC templates and Flyway use it; with several, mark one
     * {@code primary}. Spring Boot's JDBC health check and Hikari metrics cover these beans.
     *
     * @param provider       the provider name; may be omitted when exactly one provider is configured
     * @param engine         {@code postgresql}, {@code mysql} or {@code sqlserver}
     * @param host           database host (omit with {@code gcp.sql.instance})
     * @param port           port; the engine's default when omitted
     * @param database       database name
     * @param auth           how connections log in
     * @param primary        make this the primary {@code DataSource} when several are configured
     * @param pool           pool sizing and timeouts
     * @param jdbcProperties JDBC driver properties, overriding Halyx' defaults (such as the TLS mode)
     * @param properties     provider-specific settings, such as {@code gcp.sql.instance} or {@code aws.rds.region}
     */
    public record DatabaseProperties(String provider, SqlEngine engine, String host, Integer port, String database,
                                     @DefaultValue DatabaseAuthProperties auth, @DefaultValue("false") boolean primary,
                                     @DefaultValue PoolProperties pool, Map<String, String> jdbcProperties,
                                     Map<String, String> properties) {

        public DatabaseProperties {
            jdbcProperties = ordered(jdbcProperties);
            properties = ordered(properties);
        }

        /** The config with the auth resolved by the caller (a secret-backed password needs its store bean). */
        SqlConfig toSqlConfig(String name, SqlAuth resolvedAuth) {
            return toSqlConfig("halyx.databases", name, resolvedAuth);
        }

        /** The same, for the entry {@code name} of the property group {@code group}, such as {@code halyx.r2dbc-databases}. */
        SqlConfig toSqlConfig(String group, String name, SqlAuth resolvedAuth) {
            String prefix = group + "." + name;
            if (engine == null) {
                throw new IllegalStateException(prefix + ".engine is required (postgresql, mysql or sqlserver)");
            }
            if (database == null) {
                throw new IllegalStateException(prefix + ".database is required");
            }
            if (host == null && !properties.containsKey("gcp.sql.instance")) {
                throw new IllegalStateException(prefix + ".host is required");
            }
            SqlConfig.Builder config = SqlConfig.builder(engine).name(name).database(database).auth(resolvedAuth)
                    .pool(pool.toPoolSettings());
            if (host != null) {
                config.host(host);
            }
            if (port != null) {
                config.port(port);
            }
            jdbcProperties.forEach(config::jdbcProperty);
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * @param type     {@code password} or {@code iam} (RDS IAM, Entra ID, Cloud SQL IAM tokens)
     * @param username database user; for {@code iam}, the role mapped to the identity (derived on GCP if omitted)
     * @param password the password, for {@code password} without a {@code secret}
     * @param secret   read the password from a Halyx parameter store for every new connection, so a rotated
     *                 secret takes effect without a restart
     */
    public record DatabaseAuthProperties(@DefaultValue("password") Type type, String username, String password,
                                         SecretReference secret) {

        public enum Type { PASSWORD, IAM }
    }

    /**
     * A secret in one of the {@code halyx.parameter-stores}.
     *
     * @param store bean name of the parameter store
     * @param name  the secret's name
     * @param field the JSON field holding the password, for JSON secrets such as RDS-managed ones
     */
    public record SecretReference(String store, String name, String field) {
    }

    /** See {@code PoolSettings}; unset values keep its defaults. */
    public record PoolProperties(Integer maxSize, Integer minIdle, Duration connectionTimeout, Duration idleTimeout,
                                 Duration maxLifetime) {

        PoolSettings toPoolSettings() {
            PoolSettings d = PoolSettings.DEFAULTS;
            int max = maxSize == null ? d.maxSize() : maxSize;
            return new PoolSettings(max, minIdle == null ? max : minIdle,
                    connectionTimeout == null ? d.connectionTimeout() : connectionTimeout,
                    idleTimeout == null ? d.idleTimeout() : idleTimeout,
                    maxLifetime == null ? d.maxLifetime() : maxLifetime);
        }
    }

    /**
     * The Spring {@code CacheManager} behind {@code @Cacheable}, {@code @CachePut} and {@code @CacheEvict}.
     * Each Spring cache becomes a Halyx cache whose namespace is {@code namespace-prefix + name}.
     *
     * @param provider        the provider name; may be omitted when exactly one provider is configured
     * @param endpoint        {@code redis://} or {@code rediss://} server; setting it enables the cache manager
     * @param auth            how to log in
     * @param cluster         whether the server runs in cluster mode
     * @param failOpen        treat the cache as best effort: an unavailable server makes cached methods run uncached
     * @param properties      provider-specific settings, such as {@code aws.elasticache.cache-name}
     * @param defaultTtl      expiry of entries; none when omitted
     * @param ttl             per-cache expiry overriding {@code default-ttl}, keyed by cache name
     * @param namespacePrefix prefix of every cache's namespace, for sharing a server between applications
     * @param cacheNames      caches to create up front; when set, unknown names are rejected
     * @param allowNullValues whether {@code null} results are cached
     * @param codec           {@code jdk} (Java serialization) or {@code json} (Jackson 3 on Boot 4, Jackson 2 on Boot 3)
     * @param trustedPackages packages whose classes may be deserialized; required for {@code json}, and
     *                        restricts {@code jdk} too when set ({@code java.*} is always allowed)
     */
    public record CacheManagerProperties(String provider, URI endpoint, @DefaultValue CacheAuthProperties auth,
                                         @DefaultValue("false") boolean cluster, @DefaultValue("false") boolean failOpen,
                                         Map<String, String> properties, Duration defaultTtl, Map<String, Duration> ttl,
                                         @DefaultValue("") String namespacePrefix, List<String> cacheNames,
                                         @DefaultValue("true") boolean allowNullValues,
                                         @DefaultValue("jdk") Codec codec, List<String> trustedPackages) {

        public enum Codec { JDK, JSON }

        public CacheManagerProperties {
            properties = ordered(properties);
            ttl = ordered(ttl);
            cacheNames = cacheNames == null ? List.of() : List.copyOf(cacheNames);
            trustedPackages = trustedPackages == null ? List.of() : List.copyOf(trustedPackages);
        }

        public CacheConfig toCacheConfig(String cacheName) {
            CacheConfig.Builder config = CacheConfig.builder(endpoint)
                    .namespace(namespacePrefix + cacheName)
                    .cluster(cluster)
                    .failOpen(failOpen)
                    .auth(auth.toCacheAuth("cache-manager"));
            Duration expiry = ttl.getOrDefault(cacheName, defaultTtl);
            if (expiry != null) {
                config.defaultTtl(expiry);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

    /**
     * @param provider   the provider name; may be omitted when exactly one provider is configured
     * @param kind       {@code parameters} or {@code secrets}
     * @param location   Key Vault URL or App Configuration endpoint on Azure, KV mount on Vault
     * @param prefix     name prefix of every value in the store
     * @param cacheTtl   how long reads are cached in this JVM; none when omitted
     * @param nativeNames use the backend's own names as they are (see {@code ParameterStoreConfig.nativeNames})
     * @param expandJson  for {@code spring.config.import}: also expose the fields of JSON object values as
     *                    properties ({@code db/creds} holding {@code {"password":...}} adds {@code db.creds.password})
     * @param reloadInterval for {@code spring.config.import}: poll for changes this often and update the imported
     *                    properties in place; no reloading when omitted
     * @param startupAttempts for {@code spring.config.import}: attempts when the backend is temporarily unavailable
     * @param properties provider-specific settings, such as {@code aws.ssm.secure}
     */
    public record ParameterStoreProperties(String provider, @DefaultValue("secrets") ParameterKind kind, String location,
                                           @DefaultValue("") String prefix, Duration cacheTtl,
                                           @DefaultValue("false") boolean nativeNames, @DefaultValue("true") boolean expandJson,
                                           Duration reloadInterval, @DefaultValue("3") int startupAttempts,
                                           Map<String, String> properties) {

        public ParameterStoreProperties {
            properties = ordered(properties);
        }

        public ParameterStoreConfig toStoreConfig() {
            ParameterStoreConfig.Builder config = ParameterStoreConfig.builder(kind).prefix(prefix).nativeNames(nativeNames);
            if (location != null) {
                config.location(location);
            }
            if (cacheTtl != null) {
                config.cacheTtl(cacheTtl);
            }
            properties.forEach(config::property);
            return config.build();
        }
    }

    public record Retry(@DefaultValue("false") boolean enabled, @DefaultValue("3") int maxAttempts,
                        @DefaultValue("100ms") Duration initialBackoff, @DefaultValue("2s") Duration maxBackoff,
                        @DefaultValue("2.0") double multiplier) {

        RetryPolicy toPolicy() {
            return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff, multiplier);
        }
    }

    /**
     * @param enabled whether to record a {@code halyx.operation} observation (timer and span) per
     *                operation, when Micrometer is present
     */
    public record Observations(@DefaultValue("true") boolean enabled) {
    }
}
