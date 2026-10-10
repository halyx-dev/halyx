package org.kamranzafar.cirrus.spi;

import org.kamranzafar.cirrus.config.ProviderConfig;

/**
 * Plugin entry point for a cloud implementation, discovered with {@link java.util.ServiceLoader}.
 * Provider modules register an implementation in
 * {@code META-INF/services/org.kamranzafar.cirrus.spi.CloudProvider}, so adding a provider is just
 * adding its jar to the classpath.
 */
public interface CloudProvider {

    /** Stable id used in {@link ProviderConfig#of(String)}, such as {@code "aws"}. */
    String id();

    /** Opens a connection. Implementations should create native clients lazily. */
    ProviderConnection connect(ProviderConfig config);
}
