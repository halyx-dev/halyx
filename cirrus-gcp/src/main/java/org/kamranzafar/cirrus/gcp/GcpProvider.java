package org.kamranzafar.cirrus.gcp;

import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.spi.CloudProvider;
import org.kamranzafar.cirrus.spi.ProviderConnection;

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
