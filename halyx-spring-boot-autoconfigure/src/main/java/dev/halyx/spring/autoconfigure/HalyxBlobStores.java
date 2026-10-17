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

import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.cache.Cache;
import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.Codec;
import dev.halyx.events.EventPublisher;
import dev.halyx.messaging.Publisher;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.messaging.Subscription;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.spring.autoconfigure.cache.CacheCodecs;
import dev.halyx.sql.Database;
import dev.halyx.sql.SqlAuth;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamProducer;
import org.springframework.beans.factory.BeanFactory;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

/**
 * Factory behind the beans declared under {@code halyx.blob-stores}, {@code halyx.caches},
 * {@code halyx.parameter-stores}, {@code halyx.databases}, {@code halyx.document-stores}, {@code halyx.topics},
 * {@code halyx.subscriptions}, {@code halyx.stream-producers} and {@code halyx.stream-consumers}.
 *
 * <p>Each method takes the configuring {@code property} and its {@code provider} (null when omitted), resolved
 * against the {@link Halyx} bean's providers: those of {@code halyx.providers}, and any a
 * {@code HalyxBuilderCustomizer} or an application's own {@code Halyx} bean registers.
 */
public final class HalyxBlobStores {

    /** The name of this factory's bean, through which every service bean from {@code halyx.*} properties is made. */
    public static final String BEAN_NAME = "halyxBlobStores";

    private final Halyx halyx;
    private final BeanFactory beans;

    public HalyxBlobStores(Halyx halyx, BeanFactory beans) {
        this.halyx = halyx;
        this.beans = beans;
    }

    /**
     * The provider that {@code property}'s resources use: {@code provider}, which the {@code Halyx} bean must have
     * registered, or when it is null, the only provider registered.
     */
    public static CloudContext context(Halyx halyx, String property, String provider) {
        Set<String> names = halyx.names();
        if (provider == null) {
            if (names.size() == 1) {
                return halyx.provider(names.iterator().next());
            }
            throw new IllegalStateException(property + ".provider is required when " + names.size()
                    + " providers are registered " + names);
        }
        if (!names.contains(provider)) {
            throw new IllegalStateException(property + ".provider refers to unknown provider '" + provider
                    + "'. Registered providers: " + names);
        }
        return halyx.provider(provider);
    }

    private CloudContext context(String property, String provider) {
        return context(halyx, property, provider);
    }

    public BlobStore blobStore(String property, String provider, String bucket) {
        return context(property, provider).blobStore(bucket);
    }

    public Cache<String> cache(String property, String provider, CacheConfig config) {
        return context(property, provider).cache(config);
    }

    public Cache<byte[]> bytesCache(String property, String provider, CacheConfig config) {
        return context(property, provider).cache(config, Codec.bytes());
    }

    /** A cache of objects in {@code jdk} serialization or {@code json}, reading back only trusted types. */
    public Cache<Object> objectCache(String property, String provider, CacheConfig config, String codec,
                                     List<String> trustedPackages) {
        return context(property, provider).cache(config, CacheCodecs.of(codec, trustedPackages, beans, property));
    }

    /** A cache whose values the application's own {@code Codec} bean encodes. */
    public <V> Cache<V> cacheWithCodec(String property, String provider, CacheConfig config, Codec<V> codec) {
        return context(property, provider).cache(config, codec);
    }

    public ParameterStore parameterStore(String property, String provider, ParameterStoreConfig config) {
        return context(property, provider).parameterStore(config);
    }

    public DocumentStore documentStore(String property, String provider, String name,
                                       HalyxProperties.DocumentStoreProperties store) {
        return context(property, provider).documentStore(store.toTableConfig(name));
    }

    public EventPublisher eventPublisher(String property, String provider, String name,
                                         HalyxProperties.EventBusProperties bus) {
        return context(property, provider).eventPublisher(bus.toEventBusConfig(name));
    }

    public Publisher publisher(String property, String provider, String name, HalyxProperties.TopicProperties topic) {
        return context(property, provider).publisher(topic.toTopicConfig(name, null));
    }

    public Publisher publisherWithClaimCheck(String property, String provider, String name,
                                             HalyxProperties.TopicProperties topic, BlobStore claims) {
        return context(property, provider).publisher(topic.toTopicConfig(name, topic.claimCheck().toClaimCheck(claims)));
    }

    public Subscription subscription(String property, String provider, String name,
                                     HalyxProperties.SubscriptionProperties subscription) {
        return context(property, provider).subscription(subscription.toSubscriptionConfig(name, null));
    }

    public Subscription subscriptionWithClaimCheck(String property, String provider, String name,
                                                   HalyxProperties.SubscriptionProperties subscription, BlobStore claims) {
        return context(property, provider).subscription(
                subscription.toSubscriptionConfig(name, subscription.claimCheck().toClaimCheck(claims)));
    }

    public StreamProducer streamProducer(String property, String provider, String name,
                                         HalyxProperties.StreamProducerProperties producer, StreamConfig cluster) {
        return context(property, provider).streamProducer(producer.toProducerConfig(name, cluster, null));
    }

    public StreamProducer streamProducerWithClaimCheck(String property, String provider, String name,
                                                       HalyxProperties.StreamProducerProperties producer,
                                                       StreamConfig cluster, BlobStore claims) {
        return context(property, provider).streamProducer(
                producer.toProducerConfig(name, cluster, producer.claimCheck().toClaimCheck(claims)));
    }

    public StreamConsumer streamConsumer(String property, String provider, String name,
                                         HalyxProperties.StreamConsumerProperties consumer, StreamConfig cluster) {
        return context(property, provider).streamConsumer(consumer.toConsumerConfig(name, cluster, null));
    }

    public StreamConsumer streamConsumerWithClaimCheck(String property, String provider, String name,
                                                       HalyxProperties.StreamConsumerProperties consumer,
                                                       StreamConfig cluster, BlobStore claims) {
        return context(property, provider).streamConsumer(
                consumer.toConsumerConfig(name, cluster, consumer.claimCheck().toClaimCheck(claims)));
    }

    /** A pooled database with a fixed password or cloud IAM login. The pool closes with Halyx. */
    public DataSource dataSource(String property, String provider, String name,
                                 HalyxProperties.DatabaseProperties database) {
        return database(property, provider, name, database, null).dataSource();
    }

    /** A pooled database whose password is read from {@code secrets} for every new connection. */
    public DataSource dataSourceWithSecret(String property, String provider, String name,
                                           HalyxProperties.DatabaseProperties database, ParameterStore secrets) {
        return database(property, provider, name, database, secrets).dataSource();
    }

    private Database database(String property, String provider, String name, HalyxProperties.DatabaseProperties database,
                              ParameterStore secrets) {
        SqlAuth resolved = resolveAuth(property + ".auth", database.auth(), secrets);
        return context(property, provider).database(database.toSqlConfig(name, resolved));
    }

    /** The login of a database entry; {@code secrets} is its {@code auth.secret.store} bean, or null without one. */
    static SqlAuth resolveAuth(String prefix, HalyxProperties.DatabaseAuthProperties auth, ParameterStore secrets) {
        return switch (auth.type()) {
            case IAM -> auth.username() == null ? SqlAuth.iam() : SqlAuth.iam(auth.username());
            case PASSWORD -> {
                if (auth.username() == null) {
                    throw new IllegalStateException(prefix + ".username is required for password auth");
                }
                if (secrets != null) {
                    var secret = auth.secret();
                    if (secret.name() == null) {
                        throw new IllegalStateException(prefix + ".secret.name is required");
                    }
                    yield SqlAuth.password(auth.username(), () -> secret.field() == null
                            ? secrets.require(secret.name())
                            : secrets.require(secret.name(), secret.field()));
                }
                if (auth.password() == null) {
                    throw new IllegalStateException(prefix + ".password (or " + prefix + ".secret) is required");
                }
                yield SqlAuth.password(auth.username(), auth.password());
            }
        };
    }
}
