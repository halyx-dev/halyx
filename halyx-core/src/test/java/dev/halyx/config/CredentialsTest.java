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
package dev.halyx.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialsTest {

    @Test
    void impersonationStartsFromTheDefaultChain() {
        var role = Credentials.impersonate("arn:aws:iam::123456789012:role/orders");

        assertThat(role.source()).isEqualTo(Credentials.defaultChain());
        assertThat(role.sessionName()).isNull();
        assertThat(role.externalId()).isNull();
        assertThat(role.lifetime()).isNull();
    }

    @Test
    void impersonationSettingsAreKept() {
        var role = Credentials.impersonate("arn:aws:iam::123456789012:role/orders")
                .from(Credentials.of("AKIA", "s3cret"))
                .sessionName("billing")
                .externalId("acme")
                .lifetime(Duration.ofMinutes(15));

        assertThat(role.source()).isEqualTo(Credentials.of("AKIA", "s3cret"));
        assertThat(role.sessionName()).isEqualTo("billing");
        assertThat(role.externalId()).isEqualTo("acme");
        assertThat(role.lifetime()).isEqualTo(Duration.ofMinutes(15));
        assertThat(role.toString()).doesNotContain("acme", "s3cret");
    }

    @Test
    void impersonationRejectsBadSettings() {
        assertThatThrownBy(() -> Credentials.impersonate(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Credentials.impersonate("arn:aws:iam::1:role/a").from(Credentials.anonymous()))
                .hasMessageContaining("default chain or an access key");
        assertThatThrownBy(() -> Credentials.impersonate("arn:aws:iam::1:role/a")
                .from(Credentials.impersonate("arn:aws:iam::1:role/b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Credentials.impersonate("arn:aws:iam::1:role/a").lifetime(Duration.ZERO))
                .hasMessageContaining("positive");
    }

    @Test
    void servicePrincipalHasExactlyOneWayToLogIn() {
        var secret = Credentials.servicePrincipal("tenant", "client", "s3cret");
        var certificate = Credentials.servicePrincipalWithCertificate("tenant", "client", Path.of("app.pem"));
        var federated = Credentials.servicePrincipalWithFederatedToken("tenant", "client", Path.of("/var/run/token"));

        assertThat(secret.clientSecret()).isEqualTo("s3cret");
        assertThat(certificate.certificate()).isEqualTo(Path.of("app.pem"));
        assertThat(federated.federatedTokenFile()).isEqualTo(Path.of("/var/run/token"));
        assertThat(secret.toString()).doesNotContain("s3cret");
        assertThatThrownBy(() -> new Credentials.ServicePrincipal("tenant", "client", "s3cret", Path.of("app.pem"), null))
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> new Credentials.ServicePrincipal("tenant", "client", null, null, null))
                .hasMessageContaining("exactly one");
    }
}
