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
package dev.halyx.parameters;

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
