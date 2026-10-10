package org.kamranzafar.stratum.gcp;

import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.spi.CloudProvider;
import org.kamranzafar.stratum.spi.ProviderConnection;

/** The {@code "gcp"} provider. */
public final class GcpProvider implements CloudProvider {

    public static final String ID = "gcp";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ProviderConnection connect(ProviderConfig config) {
        return new GcpConnection(config);
    }
}
