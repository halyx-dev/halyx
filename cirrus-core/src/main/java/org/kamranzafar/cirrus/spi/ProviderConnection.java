package org.kamranzafar.cirrus.spi;

import org.kamranzafar.cirrus.cache.CacheConfig;
import org.kamranzafar.cirrus.cache.spi.CacheDriver;
import org.kamranzafar.cirrus.error.UnsupportedFeatureException;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.parameters.spi.ParameterDriver;
import org.kamranzafar.cirrus.sql.Database;
import org.kamranzafar.cirrus.sql.SqlConfig;
import org.kamranzafar.cirrus.storage.spi.BlobStoreDriver;

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

    @Override
    void close();
}
