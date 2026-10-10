package org.kamranzafar.cirrus.gcp;

/** GCP-specific keys for {@link org.kamranzafar.cirrus.config.ProviderConfig#property(String, String)}. */
public final class GcpProperties {

    /** Google Cloud project id. Defaults to the project of the Application Default Credentials. */
    public static final String PROJECT_ID = "gcp.project-id";

    /** Firestore database id (default {@code (default)}). */
    public static final String FIRESTORE_DATABASE = "gcp.firestore.database";

    /**
     * SQL property: connect through the Cloud SQL Java connector to this instance
     * ({@code project:region:instance}) instead of to a host. The connector must be on the classpath.
     */
    public static final String SQL_INSTANCE = "gcp.sql.instance";

    private GcpProperties() {
    }
}
