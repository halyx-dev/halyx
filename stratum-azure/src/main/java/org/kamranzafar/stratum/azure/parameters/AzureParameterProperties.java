package org.kamranzafar.stratum.azure.parameters;

/** Azure parameter store properties ({@code ParameterStoreConfig.property}). */
public final class AzureParameterProperties {

    /** App Configuration connection string ({@code Endpoint=...;Id=...;Secret=...}) instead of Entra ID. */
    public static final String APPCONFIG_CONNECTION_STRING = "azure.appconfig.connection-string";

    /** App Configuration label to read and write; the null label when unset. */
    public static final String APPCONFIG_LABEL = "azure.appconfig.label";

    /** {@code true} for local emulators only: send requests over plain HTTP. */
    public static final String INSECURE_HTTP = "azure.emulator.insecure-http";

    private AzureParameterProperties() {
    }
}
