package org.kamranzafar.stratum.spi;

import org.kamranzafar.stratum.error.UnsupportedFeatureException;
import org.kamranzafar.stratum.storage.spi.BlobStoreDriver;

/**
 * A live connection to one provider account and region: the Abstract Factory for the family of
 * service drivers (storage now; cache, SQL and search later). It owns the native SDK clients and
 * releases them on {@link #close()}.
 *
 * <p>Services a provider does not offer keep the default, which throws
 * {@link UnsupportedFeatureException}.
 */
public interface ProviderConnection extends AutoCloseable {

    default BlobStoreDriver blobStore(String bucket) {
        throw new UnsupportedFeatureException("Blob storage is not supported by this provider");
    }

    @Override
    void close();
}
