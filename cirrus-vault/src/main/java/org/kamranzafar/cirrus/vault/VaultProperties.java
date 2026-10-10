package org.kamranzafar.cirrus.vault;

/**
 * Vault provider settings, passed as {@code ProviderConfig} properties. The Vault address is the
 * provider endpoint ({@code ProviderConfig.endpoint}).
 *
 * <ul>
 *   <li>Token auth: {@code Credentials.of("token", "<token>")}, or the default chain, which reads the
 *       {@code VAULT_TOKEN} environment variable.</li>
 *   <li>AppRole: {@code vault.auth=approle}, {@code vault.approle.role-id}, {@code vault.approle.secret-id}.</li>
 *   <li>Kubernetes: {@code vault.auth=kubernetes}, {@code vault.kubernetes.role}, optionally
 *       {@code vault.kubernetes.jwt-path} (default: the pod's service account token).</li>
 *   <li>Token file (Vault Agent sink): {@code vault.auth=token-file}, {@code vault.token-file}; the file is
 *       read again whenever Vault rejects the token.</li>
 *   <li>GCP: {@code vault.auth=gcp}, {@code vault.gcp.role}: an identity token from the metadata server
 *       (GCE, GKE workload identity, Cloud Run).</li>
 *   <li>Azure: {@code vault.auth=azure}, {@code vault.azure.role}: a managed identity token plus the VM's
 *       subscription, resource group and name from the instance metadata service.</li>
 * </ul>
 *
 * <p>{@code vault.ca-certificate} names a PEM file of CA certificates to trust, for Vault servers with
 * a private CA.
 */
public final class VaultProperties {

    public static final String AUTH = "vault.auth";
    /** Mount path of the auth method; defaults to {@code approle} or {@code kubernetes}. */
    public static final String AUTH_PATH = "vault.auth-path";
    public static final String APPROLE_ROLE_ID = "vault.approle.role-id";
    public static final String APPROLE_SECRET_ID = "vault.approle.secret-id";
    public static final String KUBERNETES_ROLE = "vault.kubernetes.role";
    public static final String KUBERNETES_JWT_PATH = "vault.kubernetes.jwt-path";
    /** Vault Enterprise / HCP namespace, sent as {@code X-Vault-Namespace}. */
    public static final String NAMESPACE = "vault.namespace";
    public static final String CA_CERTIFICATE = "vault.ca-certificate";
    public static final String TOKEN_FILE = "vault.token-file";
    public static final String GCP_ROLE = "vault.gcp.role";
    /** Override of {@code http://metadata.google.internal}, for tests. */
    public static final String GCP_METADATA_URL = "vault.gcp.metadata-url";
    public static final String AZURE_ROLE = "vault.azure.role";
    /** Token audience; Vault's Azure auth expects {@code https://management.azure.com/} by default. */
    public static final String AZURE_RESOURCE = "vault.azure.resource";
    /** Override of {@code http://169.254.169.254}, for tests. */
    public static final String AZURE_METADATA_URL = "vault.azure.metadata-url";

    static final String DEFAULT_JWT_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";

    private VaultProperties() {
    }
}
