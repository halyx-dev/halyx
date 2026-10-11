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
package org.kamranzafar.cirrus.vault;

import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.parameters.spi.ParameterDriver;
import org.kamranzafar.cirrus.spi.CloudProvider;
import org.kamranzafar.cirrus.spi.ProviderConnection;

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
        VaultClient client = new VaultClient(address, config.property(VaultProperties.NAMESPACE), login(config),
                config.property(VaultProperties.CA_CERTIFICATE).map(Path::of));
        return new ProviderConnection() {
            @Override
            public ParameterDriver parameterStore(ParameterStoreConfig storeConfig) {
                return new VaultKvDriver(client, storeConfig.location().orElse("secret"), storeConfig.nativeNames());
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
            case "token-file" -> VaultLogin.tokenFile(Path.of(required(config, VaultProperties.TOKEN_FILE)));
            case "gcp" -> VaultLogin.gcp(config.property(VaultProperties.AUTH_PATH).orElse("gcp"),
                    required(config, VaultProperties.GCP_ROLE),
                    config.property(VaultProperties.GCP_METADATA_URL).orElse("http://metadata.google.internal"));
            case "azure" -> VaultLogin.azure(config.property(VaultProperties.AUTH_PATH).orElse("azure"),
                    required(config, VaultProperties.AZURE_ROLE),
                    config.property(VaultProperties.AZURE_RESOURCE).orElse("https://management.azure.com/"),
                    config.property(VaultProperties.AZURE_METADATA_URL).orElse("http://169.254.169.254"));
            default -> throw new IllegalArgumentException("Unknown " + VaultProperties.AUTH + ": " + method
                    + " (token, token-file, approle, kubernetes, gcp or azure)");
        };
    }

    private static String required(ProviderConfig config, String key) {
        return config.property(key).orElseThrow(() -> new IllegalArgumentException("Missing Vault property " + key));
    }
}
