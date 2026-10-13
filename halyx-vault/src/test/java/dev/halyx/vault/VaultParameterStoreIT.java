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
package dev.halyx.vault;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.tck.ParameterStoreConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The parameter store TCK on a Vault dev server (KV v2 at secret/), plus AppRole login and renewal. */
@Testcontainers(disabledWithoutDocker = true)
class VaultParameterStoreIT {

    private static final String ROOT = "root-token";

    @Container
    static final GenericContainer<?> VAULT = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("vault.image", "hashicorp/vault:2.1.2")))
            .withExposedPorts(8200)
            .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT)
            .withCommand("server", "-dev")
            .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200));

    private static Halyx halyx;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder()
                .register("vault", ProviderConfig.of("vault").endpoint(address()).credentials(Credentials.of("token", ROOT)))
                .build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    static URI address() {
        return URI.create("http://" + VAULT.getHost() + ":" + VAULT.getMappedPort(8200));
    }

    @Nested
    class KvSecrets extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("vault");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }
    }

    @Test
    void wrongTokenIsAccessDenied() {
        try (Halyx bad = Halyx.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .credentials(Credentials.of("token", "not-a-token"))).build()) {
            assertThatThrownBy(() -> bad.provider("vault").parameterStore(ParameterStoreConfig.secrets().build()).get("x"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    void secretsWithOtherFieldsAreReadAsJson() throws Exception {
        root("POST", "secret/data/legacy/db", "{\"data\":{\"username\":\"app\",\"password\":\"pw\"}}");

        String value = halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().build()).require("legacy/db");

        assertThat(value).contains("\"username\":\"app\"").contains("\"password\":\"pw\"");
    }

    @Test
    void appRoleLoginRenewsExpiredTokens() throws Exception {
        root("POST", "sys/auth/approle", "{\"type\":\"approle\"}");
        root("PUT", "sys/policies/acl/halyx", "{\"policy\":\"path \\\"secret/*\\\" { capabilities = [\\\"create\\\",\\\"read\\\",\\\"update\\\",\\\"delete\\\",\\\"list\\\"] }\"}");
        root("POST", "auth/approle/role/halyx", "{\"token_policies\":[\"halyx\"],\"token_ttl\":\"5s\",\"token_max_ttl\":\"5s\"}");
        String roleId = field(root("GET", "auth/approle/role/halyx/role-id", null), "role_id");
        String secretId = field(root("POST", "auth/approle/role/halyx/secret-id", "{}"), "secret_id");

        try (Halyx appRole = Halyx.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .property(VaultProperties.AUTH, "approle")
                .property(VaultProperties.APPROLE_ROLE_ID, roleId)
                .property(VaultProperties.APPROLE_SECRET_ID, secretId)).build()) {
            ParameterStore store = appRole.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("approle").build());
            store.put("token-test", "v1");
            Thread.sleep(6000); // the first token has expired
            assertThat(store.require("token-test")).isEqualTo("v1");
            store.delete("token-test");
        }
    }

    @Test
    void tokenFileIsReadAgainWhenTheTokenIsRevoked(@TempDir Path dir) throws Exception {
        String first = field(root("POST", "auth/token/create", "{\"policies\":[\"root\"]}"), "client_token");
        Path tokenFile = Files.writeString(dir.resolve("token"), first + "\n");
        try (Halyx agent = Halyx.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .property(VaultProperties.AUTH, "token-file")
                .property(VaultProperties.TOKEN_FILE, tokenFile.toString())).build()) {
            ParameterStore store = agent.provider("vault").parameterStore(ParameterStoreConfig.secrets().prefix("agent").build());
            store.put("rotating", "v1");

            // Vault Agent writes a new token to its sink; the old one is revoked.
            String second = field(root("POST", "auth/token/create", "{\"policies\":[\"root\"]}"), "client_token");
            Files.writeString(tokenFile, second);
            root("POST", "auth/token/revoke", "{\"token\":\"" + first + "\"}");

            assertThat(store.require("rotating")).isEqualTo("v1");
            store.delete("rotating");
        }
    }

    private static String root(String method, String path, String body) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(address().resolve("/v1/" + path))
                    .header("X-Vault-Token", ROOT)
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isLessThan(300);
            return response.body();
        }
    }

    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as(json).isTrue();
        return matcher.group(1);
    }
}
