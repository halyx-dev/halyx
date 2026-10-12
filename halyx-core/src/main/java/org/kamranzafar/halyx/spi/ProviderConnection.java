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
package org.kamranzafar.halyx.spi;

import org.kamranzafar.halyx.cache.CacheConfig;
import org.kamranzafar.halyx.cache.spi.CacheDriver;
import org.kamranzafar.halyx.error.UnsupportedFeatureException;
import org.kamranzafar.halyx.messaging.SubscriptionConfig;
import org.kamranzafar.halyx.messaging.TopicConfig;
import org.kamranzafar.halyx.messaging.spi.PublisherDriver;
import org.kamranzafar.halyx.messaging.spi.SubscriptionDriver;
import org.kamranzafar.halyx.nosql.TableConfig;
import org.kamranzafar.halyx.nosql.spi.DocumentStoreDriver;
import org.kamranzafar.halyx.parameters.ParameterStoreConfig;
import org.kamranzafar.halyx.parameters.spi.ParameterDriver;
import org.kamranzafar.halyx.sql.Database;
import org.kamranzafar.halyx.sql.SqlConfig;
import org.kamranzafar.halyx.storage.spi.BlobStoreDriver;

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

    /** A driver for the document table {@code config.name()}. */
    default DocumentStoreDriver documentStore(TableConfig config) {
        throw new UnsupportedFeatureException("Document stores are not supported by this provider");
    }

    @Override
    void close();
}
