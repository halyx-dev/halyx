package org.kamranzafar.cirrus.aws;

import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.spi.CloudProvider;
import org.kamranzafar.cirrus.spi.ProviderConnection;

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
