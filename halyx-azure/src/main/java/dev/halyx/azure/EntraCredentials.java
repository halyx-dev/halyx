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
package dev.halyx.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientCertificateCredentialBuilder;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.WorkloadIdentityCredentialBuilder;
import dev.halyx.config.Credentials;

/** Entra ID credentials for a configured service principal. */
final class EntraCredentials {

    private EntraCredentials() {
    }

    static TokenCredential servicePrincipal(Credentials.ServicePrincipal principal) {
        if (principal.clientSecret() != null) {
            return new ClientSecretCredentialBuilder().tenantId(principal.tenantId()).clientId(principal.clientId())
                    .clientSecret(principal.clientSecret()).build();
        }
        if (principal.certificate() != null) {
            return new ClientCertificateCredentialBuilder().tenantId(principal.tenantId()).clientId(principal.clientId())
                    .pemCertificate(principal.certificate().toString()).build();
        }
        // A federated identity credential: Entra ID trusts the token's issuer, such as an EKS or GKE cluster.
        return new WorkloadIdentityCredentialBuilder().tenantId(principal.tenantId()).clientId(principal.clientId())
                .tokenFilePath(principal.federatedTokenFile().toString()).build();
    }
}
