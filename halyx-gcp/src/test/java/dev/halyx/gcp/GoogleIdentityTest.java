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
package dev.halyx.gcp;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleIdentityTest {

    private static final String ACCOUNT = "orders@acme-prod.iam.gserviceaccount.com";
    private static final GoogleCredentials SOURCE = GoogleCredentials.create(
            new AccessToken("source", new Date(System.currentTimeMillis() + 3_600_000)));

    @Test
    void impersonatesTheServiceAccount() {
        var credentials = GoogleIdentity.impersonate(Credentials.impersonate(ACCOUNT), SOURCE);

        assertThat(credentials.getAccount()).isEqualTo(ACCOUNT);
        assertThat(credentials.getSourceCredentials()).isSameAs(SOURCE);
    }

    @Test
    void acceptsALifetime() {
        var credentials = GoogleIdentity.impersonate(Credentials.impersonate(ACCOUNT).lifetime(Duration.ofMinutes(20)),
                SOURCE);

        assertThat(credentials.getAccount()).isEqualTo(ACCOUNT);
    }

    @Test
    void refusesAwsSettings() {
        assertThatThrownBy(() -> GoogleIdentity.impersonate(Credentials.impersonate(ACCOUNT).externalId("acme"), SOURCE))
                .hasMessageContaining("AWS settings");
        assertThatThrownBy(() -> GoogleIdentity.impersonate(
                Credentials.impersonate(ACCOUNT).from(Credentials.of("AKIA", "secret")), SOURCE))
                .hasMessageContaining("Application Default Credentials");
        assertThatThrownBy(() -> GoogleIdentity.impersonate(
                Credentials.impersonate("arn:aws:iam::123456789012:role/orders"), SOURCE))
                .hasMessageContaining("service account");
    }

    @Test
    void servicePrincipalsAreForAzure() {
        try (Halyx halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp")
                .property(GcpProperties.PROJECT_ID, "acme-prod")
                .credentials(Credentials.servicePrincipal("tenant", "client", "secret"))).build()) {
            assertThatThrownBy(() -> halyx.provider("gcp").blobStore("invoices"))
                    .hasMessageContaining("Azure, not Google Cloud");
        }
    }
}
