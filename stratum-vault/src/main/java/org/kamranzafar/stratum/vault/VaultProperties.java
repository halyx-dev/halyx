package org.kamranzafar.stratum.vault;

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
 * </ul>
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

    static final String DEFAULT_JWT_PATH = "/var/run/secrets/kubernetes.io/serviceaccount/token";

    private VaultProperties() {
    }
}
