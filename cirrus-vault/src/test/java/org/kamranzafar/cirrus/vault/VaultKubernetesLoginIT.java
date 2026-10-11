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
package org.kamranzafar.cirrus.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.AccessDeniedException;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kubernetes login against Vault running in a real cluster: Vault validates the service account token
 * with the Kubernetes TokenReview API. Run with {@code scripts/vault-kubernetes-it.sh}, which sets up
 * minikube and the variables below.
 */
@EnabledIfEnvironmentVariable(named = "CIRRUS_VAULT_K8S_ADDR", matches = ".+")
class VaultKubernetesLoginIT {

    private static ProviderConfig kubernetes(String role, Path jwt) {
        return ProviderConfig.of("vault")
                .endpoint(URI.create(System.getenv("CIRRUS_VAULT_K8S_ADDR")))
                .property(VaultProperties.AUTH, "kubernetes")
                .property(VaultProperties.KUBERNETES_ROLE, role)
                .property(VaultProperties.KUBERNETES_JWT_PATH, jwt.toString());
    }

    @Test
    void serviceAccountTokenLogsIn() {
        Path jwt = Path.of(System.getenv("CIRRUS_VAULT_K8S_JWT"));
        try (Cirrus cirrus = Cirrus.builder()
                .register("vault", kubernetes(System.getenv("CIRRUS_VAULT_K8S_ROLE"), jwt)).build()) {
            var store = cirrus.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("k8s-login").build());
            store.put("db/password", "from-kubernetes");

            assertThat(store.require("db/password")).isEqualTo("from-kubernetes");
            store.delete("db/password");
        }
    }

    @Test
    void roleTheServiceAccountIsNotBoundToIsRejected() {
        Path jwt = Path.of(System.getenv("CIRRUS_VAULT_K8S_JWT"));
        try (Cirrus cirrus = Cirrus.builder().register("vault", kubernetes("no-such-role", jwt)).build()) {
            var store = cirrus.provider("vault").parameterStore(ParameterStoreConfig.secrets().build());

            assertThatThrownBy(() -> store.get("anything")).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        Path forged = Files.createTempFile("forged", ".jwt");
        Files.writeString(forged, "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJzeXN0ZW06c2VydmljZWFjY291bnQ6eDp5In0.c2lnbmF0dXJl");
        try (Cirrus cirrus = Cirrus.builder()
                .register("vault", kubernetes(System.getenv("CIRRUS_VAULT_K8S_ROLE"), forged)).build()) {
            var store = cirrus.provider("vault").parameterStore(ParameterStoreConfig.secrets().build());

            assertThatThrownBy(() -> store.get("anything")).isInstanceOf(AccessDeniedException.class);
        } finally {
            Files.deleteIfExists(forged);
        }
    }
}
