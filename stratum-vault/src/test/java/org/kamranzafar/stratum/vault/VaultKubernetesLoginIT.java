package org.kamranzafar.stratum.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.error.AccessDeniedException;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;

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
@EnabledIfEnvironmentVariable(named = "STRATUM_VAULT_K8S_ADDR", matches = ".+")
class VaultKubernetesLoginIT {

    private static ProviderConfig kubernetes(String role, Path jwt) {
        return ProviderConfig.of("vault")
                .endpoint(URI.create(System.getenv("STRATUM_VAULT_K8S_ADDR")))
                .property(VaultProperties.AUTH, "kubernetes")
                .property(VaultProperties.KUBERNETES_ROLE, role)
                .property(VaultProperties.KUBERNETES_JWT_PATH, jwt.toString());
    }

    @Test
    void serviceAccountTokenLogsIn() {
        Path jwt = Path.of(System.getenv("STRATUM_VAULT_K8S_JWT"));
        try (Stratum stratum = Stratum.builder()
                .register("vault", kubernetes(System.getenv("STRATUM_VAULT_K8S_ROLE"), jwt)).build()) {
            var store = stratum.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("k8s-login").build());
            store.put("db/password", "from-kubernetes");

            assertThat(store.require("db/password")).isEqualTo("from-kubernetes");
            store.delete("db/password");
        }
    }

    @Test
    void roleTheServiceAccountIsNotBoundToIsRejected() {
        Path jwt = Path.of(System.getenv("STRATUM_VAULT_K8S_JWT"));
        try (Stratum stratum = Stratum.builder().register("vault", kubernetes("no-such-role", jwt)).build()) {
            var store = stratum.provider("vault").parameterStore(ParameterStoreConfig.secrets().build());

            assertThatThrownBy(() -> store.get("anything")).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void forgedTokenIsRejected() throws Exception {
        Path forged = Files.createTempFile("forged", ".jwt");
        Files.writeString(forged, "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJzeXN0ZW06c2VydmljZWFjY291bnQ6eDp5In0.c2lnbmF0dXJl");
        try (Stratum stratum = Stratum.builder()
                .register("vault", kubernetes(System.getenv("STRATUM_VAULT_K8S_ROLE"), forged)).build()) {
            var store = stratum.provider("vault").parameterStore(ParameterStoreConfig.secrets().build());

            assertThatThrownBy(() -> store.get("anything")).isInstanceOf(AccessDeniedException.class);
        } finally {
            Files.deleteIfExists(forged);
        }
    }
}
