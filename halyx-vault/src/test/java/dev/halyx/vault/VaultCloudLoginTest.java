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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.json.Json;
import dev.halyx.parameters.ParameterStoreConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GCP and Azure logins against a local server acting as both the cloud metadata service and Vault,
 * checking the requests Halyx sends (the parts a real cloud cannot be emulated for).
 */
class VaultCloudLoginTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<Object> logins = new CopyOnWriteArrayList<>();
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().toString();
        requests.add(exchange.getRequestMethod() + " " + path + " " + exchange.getRequestHeaders().entrySet());
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String response;
        if (path.startsWith("/computeMetadata/v1/instance/service-accounts/default/identity")) {
            response = "gcp-identity-jwt";
        } else if (path.startsWith("/metadata/identity/oauth2/token")) {
            response = "{\"access_token\":\"azure-access-token\",\"expires_in\":\"3599\"}";
        } else if (path.startsWith("/metadata/instance")) {
            response = "{\"compute\":{\"subscriptionId\":\"sub-1\",\"resourceGroupName\":\"rg-1\",\"name\":\"vm-1\",\"vmScaleSetName\":\"\"}}";
        } else if (path.endsWith("/login")) {
            logins.add(Json.parse(body));
            response = "{\"auth\":{\"client_token\":\"cloud-token\",\"lease_duration\":3600}}";
        } else if (path.startsWith("/v1/secret/data/")) {
            String token = exchange.getRequestHeaders().getFirst("X-Vault-Token");
            if (!"cloud-token".equals(token)) {
                send(exchange, 403, "{\"errors\":[\"permission denied\"]}");
                return;
            }
            response = "{\"data\":{\"data\":{\"value\":\"from-vault\"},\"metadata\":{\"version\":1}}}";
        } else {
            send(exchange, 404, "{}");
            return;
        }
        send(exchange, 200, response);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String read(ProviderConfig config) {
        try (Halyx halyx = Halyx.builder().register("vault", config).build()) {
            return halyx.provider("vault").parameterStore(ParameterStoreConfig.secrets().build()).require("app");
        }
    }

    @Test
    void gcpLoginUsesAMetadataIdentityToken() {
        String value = read(ProviderConfig.of("vault").endpoint(URI.create(base))
                .property(VaultProperties.AUTH, "gcp")
                .property(VaultProperties.GCP_ROLE, "orders")
                .property(VaultProperties.GCP_METADATA_URL, base));

        assertThat(value).isEqualTo("from-vault");
        assertThat(logins).containsExactly(Map.of("role", "orders", "jwt", "gcp-identity-jwt"));
        assertThat(requests).anySatisfy(r -> assertThat(r)
                .contains("audience=http%3A%2F%2Fvault%2Forders").contains("format=full").contains("Metadata-flavor=[Google]"));
    }

    @Test
    void azureLoginSendsTheManagedIdentityTokenAndVmIdentity() {
        String value = read(ProviderConfig.of("vault").endpoint(URI.create(base))
                .property(VaultProperties.AUTH, "azure")
                .property(VaultProperties.AZURE_ROLE, "orders")
                .property(VaultProperties.AZURE_METADATA_URL, base));

        assertThat(value).isEqualTo("from-vault");
        assertThat(logins).containsExactly(Map.of("role", "orders", "jwt", "azure-access-token",
                "subscription_id", "sub-1", "resource_group_name", "rg-1", "vm_name", "vm-1"));
        assertThat(requests).anySatisfy(r -> assertThat(r)
                .contains("resource=https%3A%2F%2Fmanagement.azure.com%2F").contains("Metadata=[true]"));
    }
}
