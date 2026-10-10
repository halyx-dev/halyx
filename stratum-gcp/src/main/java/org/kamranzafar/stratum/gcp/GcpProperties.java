package org.kamranzafar.stratum.gcp;

/** GCP-specific keys for {@link org.kamranzafar.stratum.config.ProviderConfig#property(String, String)}. */
public final class GcpProperties {

    /** Google Cloud project id. Defaults to the project of the Application Default Credentials. */
    public static final String PROJECT_ID = "gcp.project-id";

    private GcpProperties() {
    }
}
