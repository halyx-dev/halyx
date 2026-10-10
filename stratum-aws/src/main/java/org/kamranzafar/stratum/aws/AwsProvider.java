package org.kamranzafar.stratum.aws;

import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.spi.CloudProvider;
import org.kamranzafar.stratum.spi.ProviderConnection;

/** The {@code "aws"} provider. */
public final class AwsProvider implements CloudProvider {

    public static final String ID = "aws";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ProviderConnection connect(ProviderConfig config) {
        return new AwsConnection(config);
    }
}
