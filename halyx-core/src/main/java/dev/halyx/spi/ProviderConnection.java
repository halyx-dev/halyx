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
package dev.halyx.spi;

import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.events.EventBusConfig;
import dev.halyx.events.spi.EventPublisherDriver;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.spi.FunctionInvokerDriver;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.messaging.spi.PublisherDriver;
import dev.halyx.messaging.spi.SubscriptionDriver;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.spi.DocumentStoreDriver;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.parameters.spi.ParameterDriver;
import dev.halyx.sql.Database;
import dev.halyx.sql.SqlConfig;
import dev.halyx.sql.SqlEndpoint;
import dev.halyx.storage.spi.BlobStoreDriver;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.ProducerDriver;

import java.util.Map;

/**
 * A live connection to one provider account and region: the Abstract Factory for the family of
 * service drivers (storage and cache now; SQL and search later). It owns the native SDK clients and
 * releases them on {@link #close()}.
 *
 * <p>Services a provider does not offer keep the default, which throws
 * {@link UnsupportedFeatureException}.
 */
public interface ProviderConnection extends AutoCloseable {

    default BlobStoreDriver blobStore(String bucket) {
        throw new UnsupportedFeatureException("Blob storage is not supported by this provider");
    }

    /** A driver for the cache at {@code config.endpoint()}, shared by caches with the same endpoint and auth. */
    default CacheDriver cache(CacheConfig config) {
        throw new UnsupportedFeatureException("Caches are not supported by this provider");
    }

    /** A driver for the parameter or secret store described by {@code config}. */
    default ParameterDriver parameterStore(ParameterStoreConfig config) {
        throw new UnsupportedFeatureException("Parameter stores are not supported by this provider");
    }

    /**
     * A pooled database. The connection closes it on {@link #close()}; with IAM auth the provider supplies
     * the cloud tokens.
     */
    default Database database(SqlConfig config) {
        throw new UnsupportedFeatureException("SQL databases are not supported by this provider");
    }

    /** A driver publishing to {@code config.name()}. */
    default PublisherDriver publisher(TopicConfig config) {
        throw new UnsupportedFeatureException("Messaging is not supported by this provider");
    }

    /** A driver consuming from {@code config.name()}. */
    default SubscriptionDriver subscription(SubscriptionConfig config) {
        throw new UnsupportedFeatureException("Messaging is not supported by this provider");
    }

    /** A driver publishing CloudEvents to the event bus {@code config.name()}. */
    default EventPublisherDriver eventPublisher(EventBusConfig config) {
        throw new UnsupportedFeatureException("Event buses are not supported by this provider");
    }

    /** A driver invoking the function {@code config.name()}. */
    default FunctionInvokerDriver functionInvoker(FunctionConfig config) {
        throw new UnsupportedFeatureException("Function invocation is not supported by this provider");
    }

    /** A driver sending to the stream topic {@code config.topic()} on {@code config.cluster()}. */
    default ProducerDriver streamProducer(StreamProducerConfig config) {
        throw new UnsupportedFeatureException("Streams are not supported by this provider");
    }

    /** A consumer group member reading the stream topic {@code config.topic()} on {@code config.cluster()}. */
    default ConsumerDriver streamConsumer(StreamConsumerConfig config) {
        throw new UnsupportedFeatureException("Streams are not supported by this provider");
    }

    /** The endpoint and login for {@code config}, as {@link #database} would connect. */
    default SqlEndpoint sqlEndpoint(SqlConfig config) {
        throw new UnsupportedFeatureException("SQL databases are not supported by this provider");
    }

    /** Kafka client properties for {@code cluster}, logging in as this provider's stream drivers do. */
    default Map<String, Object> kafkaClientProperties(StreamConfig cluster) {
        throw new UnsupportedFeatureException("Streams are not supported by this provider");
    }

    /** A driver for the document table {@code config.name()}. */
    default DocumentStoreDriver documentStore(TableConfig config) {
        throw new UnsupportedFeatureException("Document stores are not supported by this provider");
    }

    @Override
    void close();
}
