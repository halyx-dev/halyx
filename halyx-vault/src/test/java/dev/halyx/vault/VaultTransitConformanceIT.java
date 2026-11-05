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

import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.AccessDeniedException;
import dev.halyx.error.NotFoundException;
import dev.halyx.json.Json;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.keys.SealedData;
import dev.halyx.keys.SigningAlgorithm;
import dev.halyx.keys.WrapAlgorithm;
import dev.halyx.tck.KeyStoreConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key store kit on a Vault dev server's Transit engine. Halyx uses a token holding only the documented policy, so
 * a missing key is never created by {@code encrypt}; keys are created, rotated and withdrawn with the root token.
 */
@Testcontainers(disabledWithoutDocker = true)
class VaultTransitConformanceIT extends KeyStoreConformanceTest {

    private static final String ROOT = "root-token";
    private static final byte[] AAD = "orders/o-1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DATA = "{\"n\":1}".getBytes(StandardCharsets.UTF_8);

    /** The policy the docs give for a Halyx key store on the {@code transit} mount. */
    static final String POLICY = """
            path "transit/encrypt/*" { capabilities = ["update"] }
            path "transit/decrypt/*" { capabilities = ["update"] }
            path "transit/rewrap/*"  { capabilities = ["update"] }
            path "transit/sign/*"    { capabilities = ["update"] }
            path "transit/hmac/*"    { capabilities = ["update"] }
            path "transit/verify/*"  { capabilities = ["update"] }
            path "transit/keys/*"    { capabilities = ["read"] }
            path "transit/keys"      { capabilities = ["list"] }
            """;

    @Container
    static final GenericContainer<?> VAULT = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("vault.image", "hashicorp/vault:2.1.2")))
            .withExposedPorts(8200)
            .withEnv("VAULT_DEV_ROOT_TOKEN_ID", ROOT)
            .withCommand("server", "-dev")
            .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200));

    private static Halyx halyx;

    @BeforeAll
    static void start() throws Exception {
        root("POST", "sys/mounts/transit", Map.of("type", "transit"));
        Map.of("tck-data", "aes256-gcm96", "tck-other", "aes256-gcm96", "tck-disposable", "aes256-gcm96",
                        "tck-mac", "hmac", "tck-rsa", "rsa-2048", "tck-p256", "ecdsa-p256", "tck-p384", "ecdsa-p384")
                .forEach((name, type) -> root("POST", "transit/keys/" + name, type.equals("hmac")
                        ? Map.of("type", type, "key_size", 32) : Map.of("type", type)));
        root("PUT", "sys/policies/acl/halyx-keys", Map.of("policy", POLICY));
        withdrawn("none");
        Object token = root("POST", "auth/token/create", Map.of("policies", java.util.List.of("halyx-keys",
                "halyx-withdrawn"), "ttl", "2h"));
        halyx = Halyx.builder().register("vault", ProviderConfig.of("vault").endpoint(address())
                .credentials(Credentials.of("token", (String) Json.at(token, "auth", "client_token")))).build();
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

    @Override
    protected CloudContext context() {
        return halyx.provider("vault");
    }

    @Override
    protected KeyStoreConfig.Builder config() {
        return KeyStoreConfig.builder();
    }

    @Override
    protected String encryptionKey() {
        return "tck-data";
    }

    @Override
    protected String otherEncryptionKey() {
        return "tck-other";
    }

    @Override
    protected String disposableKey() {
        return "tck-disposable";
    }

    @Override
    protected String signingKey(SigningAlgorithm algorithm) {
        return switch (algorithm) {
            case RSA_PSS_SHA256, RSA_PKCS1_SHA256 -> "tck-rsa";
            case ECDSA_P256_SHA256 -> "tck-p256";
            case ECDSA_P384_SHA384 -> "tck-p384";
        };
    }

    @Override
    protected Optional<String> macKey() {
        return Optional.of("tck-mac");
    }

    @Override
    protected void rotate(String name) {
        root("POST", "transit/keys/" + name + "/rotate", Map.of());
    }

    /** Transit has no disabled state: a key is withdrawn by denying its use in the policy, which applies at once. */
    @Override
    protected void disable(String name) {
        withdrawn(name);
    }

    @Override
    protected void enable(String name) {
        withdrawn("none");
    }

    @Override
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of("aDisabledKeyRefusesDecryption", "Transit has no disabled state for a key's metadata to "
                + "report; the refusal itself is checked by aKeyWithdrawnByPolicyRefusesDecryption");
    }

    // Vault's own cases

    @Test
    void aKeyWithdrawnByPolicyRefusesDecryption() {
        KeyStore store = store();
        SealedData sealed = store.key(disposableKey()).encrypt(DATA, AAD);
        disable(disposableKey());
        try {
            assertThatThrownBy(() -> store.decrypt(sealed, AAD)).isInstanceOf(AccessDeniedException.class)
                    .satisfies(e -> assertThat(((AccessDeniedException) e).isRetryable()).isFalse());
        } finally {
            enable(disposableKey());
        }
        assertThat(store.decrypt(sealed, AAD)).isEqualTo(DATA);
    }

    @Test
    void encryptingWithAMissingKeyDoesNotCreateIt() throws Exception {
        CryptoKey missing = store().key("tck-never-created");

        assertThatThrownBy(() -> missing.encrypt(DATA, AAD)).isInstanceOf(NotFoundException.class);

        assertThat(rootStatus("GET", "transit/keys/tck-never-created")).isEqualTo(404);
    }

    @Test
    void sealedDataNamesTheMountAndTheTransitVersion() {
        SealedData sealed = store().key(encryptionKey()).encrypt(DATA, AAD);

        // The entry's version is the one Transit wrapped with, as its ciphertext says.
        assertThat(sealed.keys()).singleElement().satisfies(entry -> {
            assertThat(entry.keyId()).isEqualTo("vault-transit:transit/tck-data");
            assertThat(entry.algorithm()).contains(WrapAlgorithm.VAULT_TRANSIT);
            assertThat(new String(entry.bytes(), StandardCharsets.UTF_8))
                    .startsWith("vault:v" + entry.version().orElseThrow() + ":");
        });
    }

    @Test
    void anotherMountIsAnotherStore() {
        root("POST", "sys/mounts/transit-eu", Map.of("type", "transit"));
        root("PUT", "sys/policies/acl/halyx-keys", Map.of("policy", POLICY + POLICY.replace("transit/", "transit-eu/")));
        root("POST", "transit-eu/keys/tck-data", Map.of("type", "aes256-gcm96"));
        KeyStore eu = context().keyStore(KeyStoreConfig.builder().location("transit-eu").build());

        SealedData sealed = eu.key(encryptionKey()).encrypt(DATA, AAD);

        assertThat(sealed.keys().get(0).keyId()).isEqualTo("vault-transit:transit-eu/tck-data");
        assertThat(eu.decrypt(sealed, AAD)).isEqualTo(DATA);
        assertThatThrownBy(() -> store().decrypt(sealed, AAD)).isInstanceOf(dev.halyx.error.InvalidArgumentException.class);
    }

    // Helpers

    /** Denies every use of {@code name} on top of the key policy ({@code none} names no key). */
    private static void withdrawn(String name) {
        // Exact paths: Vault applies the one matching path of highest priority, and transit/encrypt/* outranks a
        // pattern with an earlier wildcard.
        StringBuilder policy = new StringBuilder();
        for (String operation : java.util.List.of("encrypt", "decrypt", "rewrap", "sign", "keys")) {
            policy.append("path \"transit/").append(operation).append('/').append(name)
                    .append("\" { capabilities = [\"deny\"] }\n");
        }
        for (String operation : java.util.List.of("hmac", "verify")) {
            policy.append("path \"transit/").append(operation).append('/').append(name)
                    .append("/sha2-256\" { capabilities = [\"deny\"] }\n");
        }
        root("PUT", "sys/policies/acl/halyx-withdrawn", Map.of("policy", policy.toString()));
    }

    private static Object root(String method, String path, Object body) {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(address().resolve("/v1/" + path))
                    .header("X-Vault-Token", ROOT)
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isLessThan(300);
            return response.body().isEmpty() ? null : Json.parse(response.body());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path, e);
        }
    }

    private static int rootStatus(String method, String path) throws Exception {
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(address().resolve("/v1/" + path))
                    .header("X-Vault-Token", ROOT).method(method, HttpRequest.BodyPublishers.noBody()).build();
            return http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        }
    }
}
