package org.kamranzafar.stratum.azure;

/** Azure-specific keys for {@link org.kamranzafar.stratum.config.ProviderConfig#property(String, String)}. */
public final class AzureProperties {

    /**
     * Storage account name. Used to derive the endpoint {@code https://<account>.blob.core.windows.net}
     * when no endpoint override is set. Defaults to the id of {@code Credentials.Static}.
     */
    public static final String STORAGE_ACCOUNT = "azure.storage.account";

    private AzureProperties() {
    }
}
