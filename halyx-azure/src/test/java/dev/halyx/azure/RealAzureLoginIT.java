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

import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cloud logins on real Azure (tier 5): the test service principal named explicitly, with its client secret and
 * with a federated token, against a storage account it may use. The federated token is a Google ID token for the
 * Google Cloud test service account, which the application trusts through a federated identity credential: code on
 * Google Cloud logging in to Azure with no stored secret. Runs only with {@code -Dhalyx.real.azure.logins=true},
 * from {@code cloud-testing/scripts/azure-real-cloud.sh}, which passes the storage account and container, and the
 * token file when one could be made; the principal comes from {@code AZURE_TENANT_ID}, {@code AZURE_CLIENT_ID} and
 * {@code AZURE_CLIENT_SECRET}.
 */
@EnabledIfSystemProperty(named = "halyx.real.azure.logins", matches = "true")
class RealAzureLoginIT {

    private static final String RUN = System.getProperty("halyx.real.azure.run", "local");
    private static final String STORAGE_ACCOUNT = System.getProperty("halyx.real.azure.logins.storage-account");
    private static final String CONTAINER = System.getProperty("halyx.real.azure.logins.container");
    private static final String TOKEN_FILE = System.getProperty("halyx.real.azure.logins.token-file", "");
    private static final String TENANT = System.getenv("AZURE_TENANT_ID");
    private static final String CLIENT = System.getenv("AZURE_CLIENT_ID");

    private static Halyx halyx(Credentials credentials) {
        return Halyx.builder().register("azure", ProviderConfig.of("azure").credentials(credentials)
                .property(AzureProperties.STORAGE_ACCOUNT, STORAGE_ACCOUNT)).build();
    }

    private static void roundTrip(Credentials credentials, String content) {
        try (Halyx halyx = halyx(credentials)) {
            BlobStore files = halyx.provider("azure").blobStore(CONTAINER);
            BlobKey key = BlobKey.of("logins/" + RUN + "-" + content + ".txt");

            files.put(key, BlobContent.ofString(content));

            assertThat(files.getBytes(key)).asString().isEqualTo(content);
            files.delete(key);
        }
    }

    private static void assertRefused(Credentials credentials, String reason) {
        try (Halyx halyx = halyx(credentials)) {
            BlobStore files = halyx.provider("azure").blobStore(CONTAINER);

            assertThatThrownBy(() -> files.exists(BlobKey.of("any"))).satisfies(error -> {
                List<String> messages = new ArrayList<>();
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    messages.add(String.valueOf(cause.getMessage()));
                }
                assertThat(String.join("\n", messages)).contains(reason);
            });
        }
    }

    @Test
    void aServicePrincipalLogsInWithItsSecret() {
        roundTrip(Credentials.servicePrincipal(TENANT, CLIENT, System.getenv("AZURE_CLIENT_SECRET")), "secret");
    }

    @Test
    void aWrongSecretIsRefused() {
        // AADSTS7000215: an invalid client secret
        assertRefused(Credentials.servicePrincipal(TENANT, CLIENT, "not-the-secret"), "AADSTS7000215");
    }

    @Test
    void aServicePrincipalLogsInWithAFederatedTokenFromGoogleCloud() {
        assumeTrue(!TOKEN_FILE.isBlank(), "no federated token file: see the tier 5 setup in docs/cloud-testing/azure.md");
        roundTrip(Credentials.servicePrincipalWithFederatedToken(TENANT, CLIENT, Path.of(TOKEN_FILE)), "federated");
    }

    @Test
    void aTokenFromAnUntrustedIssuerIsRefused(@TempDir Path dir) throws Exception {
        // A well-formed but unsigned token from an issuer the application doesn't trust
        String header = encode("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String claims = encode("{\"iss\":\"https://untrusted.example\",\"sub\":\"halyx\",\"aud\":\"api://AzureADTokenExchange\","
                + "\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}");
        Path token = Files.writeString(dir.resolve("token"), header + "." + claims + ".");
        assertRefused(Credentials.servicePrincipalWithFederatedToken(TENANT, CLIENT, token), "AADSTS");
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes());
    }
}
