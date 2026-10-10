package org.kamranzafar.stratum.vault;

import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.kamranzafar.stratum.parameters.spi.ParameterDriver;
import org.kamranzafar.stratum.spi.CloudProvider;
import org.kamranzafar.stratum.spi.ProviderConnection;

import java.net.URI;
import java.nio.file.Path;

/**
 * The {@code "vault"} provider: HashiCorp Vault (including HCP Vault). Both parameter kinds use the
 * KV v2 engine mounted at the store's location (default {@code secret}).
 */
public final class VaultProvider implements CloudProvider {

    public static final String ID = "vault";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ProviderConnection connect(ProviderConfig config) {
        URI address = config.endpoint().orElseThrow(() ->
                new IllegalArgumentException("The vault provider needs the Vault address as its endpoint"));
        VaultClient client = new VaultClient(address, config.property(VaultProperties.NAMESPACE), login(config));
        return new ProviderConnection() {
            @Override
            public ParameterDriver parameterStore(ParameterStoreConfig storeConfig) {
                return new VaultKvDriver(client, storeConfig.location().orElse("secret"));
            }

            @Override
            public void close() {
                client.close();
            }
        };
    }

    private static VaultLogin login(ProviderConfig config) {
        String method = config.property(VaultProperties.AUTH).orElse("token");
        return switch (method) {
            case "token" -> switch (config.credentials()) {
                case Credentials.Static s -> VaultLogin.token(s.secret());
                case Credentials.DefaultChain ignored -> {
                    String env = System.getenv("VAULT_TOKEN");
                    if (env == null || env.isBlank()) {
                        throw new IllegalArgumentException("No Vault token: set VAULT_TOKEN, use Credentials.of(\"token\", "
                                + "\"<token>\"), or configure vault.auth=approle|kubernetes");
                    }
                    yield VaultLogin.token(env);
                }
                case Credentials.Anonymous ignored -> throw new IllegalArgumentException("Vault needs authentication");
            };
            case "approle" -> VaultLogin.appRole(config.property(VaultProperties.AUTH_PATH).orElse("approle"),
                    required(config, VaultProperties.APPROLE_ROLE_ID), required(config, VaultProperties.APPROLE_SECRET_ID));
            case "kubernetes" -> VaultLogin.kubernetes(config.property(VaultProperties.AUTH_PATH).orElse("kubernetes"),
                    required(config, VaultProperties.KUBERNETES_ROLE),
                    Path.of(config.property(VaultProperties.KUBERNETES_JWT_PATH).orElse(VaultProperties.DEFAULT_JWT_PATH)));
            default -> throw new IllegalArgumentException("Unknown " + VaultProperties.AUTH + ": " + method
                    + " (token, approle or kubernetes)");
        };
    }

    private static String required(ProviderConfig config, String key) {
        return config.property(key).orElseThrow(() -> new IllegalArgumentException("Missing Vault property " + key));
    }
}
