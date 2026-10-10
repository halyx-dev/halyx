package org.kamranzafar.stratum.spring.autoconfigure;

import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.storage.BlobStore;

/** Factory behind the {@code BlobStore} beans declared under {@code stratum.blob-stores}. */
public final class StratumBlobStores {

    private final Stratum stratum;

    public StratumBlobStores(Stratum stratum) {
        this.stratum = stratum;
    }

    public BlobStore blobStore(String provider, String bucket) {
        return stratum.provider(provider).blobStore(bucket);
    }
}
