package org.kamranzafar.stratum.azure;

import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.spi.CloudProvider;
import org.kamranzafar.stratum.spi.ProviderConnection;

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
