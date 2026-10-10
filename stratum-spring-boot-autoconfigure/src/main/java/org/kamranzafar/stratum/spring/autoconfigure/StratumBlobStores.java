package org.kamranzafar.stratum.spring.autoconfigure;

import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.Cache;
import org.kamranzafar.stratum.cache.CacheConfig;
import org.kamranzafar.stratum.parameters.ParameterStore;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.kamranzafar.stratum.storage.BlobStore;

/** Factory behind the {@code BlobStore} and {@code Cache} beans declared under {@code stratum.blob-stores} and {@code stratum.caches}. */
public final class StratumBlobStores {

    private final Stratum stratum;

    public StratumBlobStores(Stratum stratum) {
        this.stratum = stratum;
    }

    public BlobStore blobStore(String provider, String bucket) {
        return stratum.provider(provider).blobStore(bucket);
    }

    public Cache<String> cache(String provider, CacheConfig config) {
        return stratum.provider(provider).cache(config);
    }

    public ParameterStore parameterStore(String provider, ParameterStoreConfig config) {
        return stratum.provider(provider).parameterStore(config);
    }
}
