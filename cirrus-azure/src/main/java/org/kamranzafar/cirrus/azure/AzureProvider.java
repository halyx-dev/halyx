package org.kamranzafar.cirrus.azure;

import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.spi.CloudProvider;
import org.kamranzafar.cirrus.spi.ProviderConnection;

/** The {@code "azure"} provider. */
public final class AzureProvider implements CloudProvider {

    public static final String ID = "azure";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ProviderConnection connect(ProviderConfig config) {
        return new AzureConnection(config);
    }
}
