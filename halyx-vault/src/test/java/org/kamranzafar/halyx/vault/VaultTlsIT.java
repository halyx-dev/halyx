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
package org.kamranzafar.halyx.vault;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.config.Credentials;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.error.AccessDeniedException;
import org.kamranzafar.halyx.parameters.ParameterStoreConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Vault with TLS from a private CA (the dev server's generated one), trusted via vault.ca-certificate. */
@Testcontainers(disabledWithoutDocker = true)
class VaultTlsIT {

    @TempDir
    static Path dir;

    private static GenericContainer<?> vault;
    private static Path ca;

    @BeforeAll
    static void start() {
        vault = new GenericContainer<>(DockerImageName.parse(System.getProperty("vault.image", "hashicorp/vault:2.1.2")))
                .withExposedPorts(8200)
                .withEnv("VAULT_DEV_ROOT_TOKEN_ID", "root-token")
                // -dev-tls needs its certificate directory to exist.
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
                .withCommand("-c", "mkdir -p /tmp/tls && exec docker-entrypoint.sh server -dev -dev-tls "
                        + "-dev-tls-cert-dir=/tmp/tls -dev-listen-address=0.0.0.0:8200")
                .waitingFor(Wait.forLogMessage(".*Development mode should NOT be used.*", 1).withStartupTimeout(Duration.ofMinutes(1)));
        vault.start();
        ca = dir.resolve("vault-ca.pem");
        vault.copyFileFromContainer("/tmp/tls/vault-ca.pem", ca.toString());
    }

    @AfterAll
    static void stop() {
        if (vault != null) {
            vault.stop();
        }
    }

    private static ProviderConfig config() {
        return ProviderConfig.of("vault")
                .endpoint(URI.create("https://localhost:" + vault.getMappedPort(8200)))
                .credentials(Credentials.of("token", "root-token"));
    }

    @Test
    void trustsAPrivateCa() {
        try (Halyx halyx = Halyx.builder().register("vault", config()
                .property(VaultProperties.CA_CERTIFICATE, ca.toString())).build()) {
            var store = halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("tls").build());
            store.put("check", "over-tls");

            assertThat(store.require("check")).isEqualTo("over-tls");
            store.delete("check");
        }
    }

    @Test
    void untrustedCertificateIsAConfigurationError() {
        try (Halyx halyx = Halyx.builder().register("vault", config()).build()) {
            var store = halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().build());

            assertThatThrownBy(() -> store.get("check"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining(VaultProperties.CA_CERTIFICATE);
        }
    }
}
