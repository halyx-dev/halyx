package org.kamranzafar.cirrus.memory;

import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.cache.spi.CacheDriver;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.messaging.spi.PublisherDriver;
import org.kamranzafar.cirrus.messaging.spi.SubscriptionDriver;
import org.kamranzafar.cirrus.nosql.TableConfig;
import org.kamranzafar.cirrus.nosql.spi.DocumentStoreDriver;
import org.kamranzafar.cirrus.parameters.Parameter;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.parameters.spi.ParameterDriver;
import org.kamranzafar.cirrus.spi.CloudProvider;
import org.kamranzafar.cirrus.spi.ProviderConnection;
import org.kamranzafar.cirrus.storage.spi.BlobStoreDriver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The {@code "memory"} provider: a Service Stub that keeps everything in the JVM heap. Use it for unit
 * tests and local development, with {@code ProviderConfig.of("memory")}. Buckets and caches are created
 * on first use (any {@code redis://} endpoint works), and each connection has its own isolated data.
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
        public DocumentStoreDriver documentStore(TableConfig config) {
            return new InMemoryDocumentStoreDriver(config,
                    tables.computeIfAbsent(config.name(), t -> new InMemoryDocumentStoreDriver.Table()));
        }

        @Override
        public void close() {
            tables.clear();
            broker.clear();
            buckets.clear();
            caches.clear();
            parameters.clear();
        }
    }
}
