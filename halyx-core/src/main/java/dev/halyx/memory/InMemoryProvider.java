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
package dev.halyx.memory;

import dev.halyx.cache.CacheConfig;
import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.config.ProviderConfig;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.messaging.spi.PublisherDriver;
import dev.halyx.messaging.spi.SubscriptionDriver;
import dev.halyx.nosql.TableConfig;
import dev.halyx.nosql.spi.DocumentStoreDriver;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.parameters.spi.ParameterDriver;
import dev.halyx.spi.CloudProvider;
import dev.halyx.spi.ProviderConnection;
import dev.halyx.storage.spi.BlobStoreDriver;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.ProducerDriver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The {@code "memory"} provider: a Service Stub that keeps everything in the JVM heap. Use it for unit
 * tests and local development, with {@code ProviderConfig.of("memory")}. Buckets, caches and stream topics
 * are created on first use (any {@code redis://} endpoint works), and each connection has its own isolated data.
 */
public final class InMemoryProvider implements CloudProvider {

    public static final String ID = "memory";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ProviderConnection connect(ProviderConfig config) {
        return new Connection();
    }

    private static final class Connection implements ProviderConnection {

        private final Map<String, ConcurrentNavigableMap<String, InMemoryBlobStoreDriver.Entry>> buckets =
                new ConcurrentHashMap<>();
        /** One store per endpoint, shared by every namespace on it, like a Redis server. */
        private final Map<String, CacheDriver> caches = new ConcurrentHashMap<>();
        private final Map<String, ConcurrentSkipListMap<String, List<Parameter>>> parameters = new ConcurrentHashMap<>();
        private final InMemoryBroker broker = new InMemoryBroker();
        private final InMemoryStreams streams = new InMemoryStreams();
        private final Map<String, InMemoryDocumentStoreDriver.Table> tables = new ConcurrentHashMap<>();

        @Override
        public BlobStoreDriver blobStore(String bucket) {
            return new InMemoryBlobStoreDriver(bucket, buckets.computeIfAbsent(bucket, b -> new ConcurrentSkipListMap<>()));
        }

        @Override
        public CacheDriver cache(CacheConfig config) {
            String server = config.endpoint().getHost() + ":" + config.endpoint().getPort();
            return caches.computeIfAbsent(server, s -> new InMemoryCacheDriver());
        }

        @Override
        public ParameterDriver parameterStore(ParameterStoreConfig config) {
            var values = parameters.computeIfAbsent(config.kind() + "|" + config.location().orElse(""),
                    k -> new ConcurrentSkipListMap<>());
            return new InMemoryParameterDriver(values, config.nativeNames());
        }

        @Override
        public PublisherDriver publisher(TopicConfig config) {
            return broker.publisher(config);
        }

        @Override
        public SubscriptionDriver subscription(SubscriptionConfig config) {
            return broker.subscription(config);
        }

        @Override
        public ProducerDriver streamProducer(StreamProducerConfig config) {
            return streams.producer(config);
        }

        @Override
        public ConsumerDriver streamConsumer(StreamConsumerConfig config) {
            return streams.consumer(config);
        }

        @Override
        public DocumentStoreDriver documentStore(TableConfig config) {
            return new InMemoryDocumentStoreDriver(config,
                    tables.computeIfAbsent(config.name(), t -> new InMemoryDocumentStoreDriver.Table()));
        }

        @Override
        public void close() {
            tables.clear();
            broker.clear();
            streams.clear();
            buckets.clear();
            caches.clear();
            parameters.clear();
        }
    }
}
