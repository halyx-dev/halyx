package org.kamranzafar.cirrus.gcp;

/** GCP-specific keys for {@link org.kamranzafar.cirrus.config.ProviderConfig#property(String, String)}. */
public final class GcpProperties {

    /** Google Cloud project id. Defaults to the project of the Application Default Credentials. */
    public static final String PROJECT_ID = "gcp.project-id";

    private GcpProperties() {
    }
}
