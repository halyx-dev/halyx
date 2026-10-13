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

import dev.halyx.Halyx;
import dev.halyx.cache.Cache;
import dev.halyx.cache.CacheConfig;
import dev.halyx.messaging.Publisher;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.messaging.Subscription;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.sql.Database;
import dev.halyx.sql.SqlAuth;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamProducer;

import javax.sql.DataSource;

/**
 * Factory behind the beans declared under {@code halyx.blob-stores}, {@code halyx.caches},
 * {@code halyx.parameter-stores}, {@code halyx.databases}, {@code halyx.document-stores}, {@code halyx.topics},
 * {@code halyx.subscriptions}, {@code halyx.stream-producers} and {@code halyx.stream-consumers}.
 */
public final class HalyxBlobStores {

    private final Halyx halyx;

    public HalyxBlobStores(Halyx halyx) {
        this.halyx = halyx;
    }

    public BlobStore blobStore(String provider, String bucket) {
        return halyx.provider(provider).blobStore(bucket);
    }

    public Cache<String> cache(String provider, CacheConfig config) {
        return halyx.provider(provider).cache(config);
    }

    public ParameterStore parameterStore(String provider, ParameterStoreConfig config) {
        return halyx.provider(provider).parameterStore(config);
    }

    public DocumentStore documentStore(String provider, String name, HalyxProperties.DocumentStoreProperties store) {
        return halyx.provider(provider).documentStore(store.toTableConfig(name));
    }

    public Publisher publisher(String provider, String name, HalyxProperties.TopicProperties topic) {
        return halyx.provider(provider).publisher(topic.toTopicConfig(name, null));
    }

    public Publisher publisherWithClaimCheck(String provider, String name, HalyxProperties.TopicProperties topic,
                                             BlobStore claims) {
        return halyx.provider(provider).publisher(topic.toTopicConfig(name, topic.claimCheck().toClaimCheck(claims)));
    }

    public Subscription subscription(String provider, String name, HalyxProperties.SubscriptionProperties subscription) {
        return halyx.provider(provider).subscription(subscription.toSubscriptionConfig(name, null));
    }

    public Subscription subscriptionWithClaimCheck(String provider, String name,
                                                   HalyxProperties.SubscriptionProperties subscription, BlobStore claims) {
        return halyx.provider(provider).subscription(
                subscription.toSubscriptionConfig(name, subscription.claimCheck().toClaimCheck(claims)));
    }

    public StreamProducer streamProducer(String provider, String name, HalyxProperties.StreamProducerProperties producer,
                                         StreamConfig cluster) {
        return halyx.provider(provider).streamProducer(producer.toProducerConfig(name, cluster, null));
    }

    public StreamProducer streamProducerWithClaimCheck(String provider, String name,
                                                       HalyxProperties.StreamProducerProperties producer,
                                                       StreamConfig cluster, BlobStore claims) {
        return halyx.provider(provider).streamProducer(
                producer.toProducerConfig(name, cluster, producer.claimCheck().toClaimCheck(claims)));
    }

    public StreamConsumer streamConsumer(String provider, String name, HalyxProperties.StreamConsumerProperties consumer,
                                         StreamConfig cluster) {
        return halyx.provider(provider).streamConsumer(consumer.toConsumerConfig(name, cluster, null));
    }

    public StreamConsumer streamConsumerWithClaimCheck(String provider, String name,
                                                       HalyxProperties.StreamConsumerProperties consumer,
                                                       StreamConfig cluster, BlobStore claims) {
        return halyx.provider(provider).streamConsumer(
                consumer.toConsumerConfig(name, cluster, consumer.claimCheck().toClaimCheck(claims)));
    }

    /** A pooled database with a fixed password or cloud IAM login. The pool closes with Halyx. */
    public DataSource dataSource(String provider, String name, HalyxProperties.DatabaseProperties database) {
        return database(provider, name, database, null).dataSource();
    }

    /** A pooled database whose password is read from {@code secrets} for every new connection. */
    public DataSource dataSourceWithSecret(String provider, String name, HalyxProperties.DatabaseProperties database,
                                           ParameterStore secrets) {
        return database(provider, name, database, secrets).dataSource();
    }

    private Database database(String provider, String name, HalyxProperties.DatabaseProperties database,
                              ParameterStore secrets) {
        String prefix = "halyx.databases." + name + ".auth";
        var auth = database.auth();
        SqlAuth resolved = switch (auth.type()) {
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
        return halyx.provider(provider).database(database.toSqlConfig(name, resolved));
    }
}
