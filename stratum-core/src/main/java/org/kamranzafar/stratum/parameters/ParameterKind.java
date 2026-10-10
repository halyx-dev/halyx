package org.kamranzafar.stratum.parameters;

/**
 * What a store holds, which decides the backing service on each cloud.
 *
 * <table>
 *   <caption>Backing services</caption>
 *   <tr><th>Kind</th><th>AWS</th><th>Azure</th><th>GCP</th><th>Vault</th></tr>
 *   <tr><td>PARAMETERS</td><td>SSM Parameter Store</td><td>App Configuration</td><td>Parameter Manager</td><td>KV v2</td></tr>
 *   <tr><td>SECRETS</td><td>Secrets Manager</td><td>Key Vault</td><td>Secret Manager</td><td>KV v2</td></tr>
 * </table>
 */
public enum ParameterKind {
    /** Plain configuration values. */
    PARAMETERS,
    /** Credentials and other sensitive values, encrypted at rest and access-audited by the backend. */
    SECRETS
}
