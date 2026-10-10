package org.kamranzafar.stratum.memory;

import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.spi.CloudProvider;
import org.kamranzafar.stratum.spi.ProviderConnection;
import org.kamranzafar.stratum.storage.spi.BlobStoreDriver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The {@code "memory"} provider: a Service Stub that keeps everything in the JVM heap. Use it for unit
 * tests and local development, with {@code ProviderConfig.of("memory")}. Buckets are created on first
 * use, and each connection has its own isolated data.
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

        @Override
        public BlobStoreDriver blobStore(String bucket) {
            return new InMemoryBlobStoreDriver(bucket, buckets.computeIfAbsent(bucket, b -> new ConcurrentSkipListMap<>()));
        }

        @Override
        public void close() {
            buckets.clear();
        }
    }
}
