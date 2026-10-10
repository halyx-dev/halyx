package org.kamranzafar.cirrus.spring.autoconfigure;

import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.storage.BlobStore;

/** Factory behind the {@code BlobStore} and {@code Cache} beans declared under {@code cirrus.blob-stores} and {@code cirrus.caches}. */
public final class CirrusBlobStores {

    private final Cirrus cirrus;

    public CirrusBlobStores(Cirrus cirrus) {
        this.cirrus = cirrus;
    }

    public BlobStore blobStore(String provider, String bucket) {
        return cirrus.provider(provider).blobStore(bucket);
    }

    public Cache<String> cache(String provider, CacheConfig config) {
        return cirrus.provider(provider).cache(config);
    }

    public ParameterStore parameterStore(String provider, ParameterStoreConfig config) {
        return cirrus.provider(provider).parameterStore(config);
    }
}
