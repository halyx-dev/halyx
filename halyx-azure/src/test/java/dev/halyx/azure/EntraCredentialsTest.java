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

import com.azure.identity.ClientCertificateCredential;
import com.azure.identity.ClientSecretCredential;
import com.azure.identity.WorkloadIdentityCredential;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntraCredentialsTest {

    @Test
    void eachKindOfServicePrincipalHasItsCredential() {
        assertThat(EntraCredentials.servicePrincipal(Credentials.servicePrincipal("tenant", "client", "secret")))
                .isInstanceOf(ClientSecretCredential.class);
        assertThat(EntraCredentials.servicePrincipal(
                Credentials.servicePrincipalWithCertificate("tenant", "client", Path.of("app.pem"))))
                .isInstanceOf(ClientCertificateCredential.class);
        assertThat(EntraCredentials.servicePrincipal(
                Credentials.servicePrincipalWithFederatedToken("tenant", "client", Path.of("/var/run/token"))))
                .isInstanceOf(WorkloadIdentityCredential.class);
    }

    @Test
    void azureDoesNotImpersonate() {
        try (Halyx halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")
                .property(AzureProperties.STORAGE_ACCOUNT, "acme")
                .credentials(Credentials.impersonate("arn:aws:iam::123456789012:role/orders"))).build()) {
            assertThatThrownBy(() -> halyx.provider("azure").blobStore("invoices"))
                    .hasMessageContaining("not by impersonation");
        }
    }
}
