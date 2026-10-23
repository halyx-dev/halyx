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

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Storage;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cloud logins on real Google Cloud (tier 5): a service account impersonated from Application Default Credentials,
 * using a bucket the run grants it, and one the test identity may not impersonate. Runs only with
 * {@code -Dhalyx.real.gcp.logins=true}, from {@code cloud-testing/scripts/gcp-real-cloud.sh}, which passes the
 * project, the bucket, the target service account and its unique ID, and the one it may not act as.
 */
@EnabledIfSystemProperty(named = "halyx.real.gcp.logins", matches = "true")
class RealGcpLoginIT {

    private static final String PROJECT = System.getProperty("halyx.real.gcp.project");
    private static final String RUN = System.getProperty("halyx.real.gcp.run", "local");
    private static final String BUCKET = System.getProperty("halyx.real.gcp.logins.bucket");
    private static final String TARGET = System.getProperty("halyx.real.gcp.logins.target");
    private static final String TARGET_ID = System.getProperty("halyx.real.gcp.logins.target-id");
    private static final String DENIED = System.getProperty("halyx.real.gcp.logins.denied-target");

    private static Halyx halyx(Credentials credentials) {
        return Halyx.builder().register("gcp", ProviderConfig.of("gcp").credentials(credentials)
                .property(GcpProperties.PROJECT_ID, PROJECT)).build();
    }

    /**
     * What Google's token endpoint reports for the credentials a blob store calls with, without whitespace. With only
     * the cloud-platform scope it gives no email, but its {@code azp} is the service account's unique ID.
     */
    private static String tokenInfo(BlobStore store) throws Exception {
        var credentials = (GoogleCredentials) store.unwrap(Storage.class).getOptions().getCredentials();
        if (credentials.createScopedRequired()) {
            // As the client scopes them for its calls; unscoped, Google refuses the token request
            credentials = credentials.createScoped("https://www.googleapis.com/auth/cloud-platform");
        }
        credentials.refreshIfExpired();
        String token = credentials.getAccessToken().getTokenValue();
        try (HttpClient http = HttpClient.newHttpClient()) {
            HttpResponse<String> info = http.send(HttpRequest.newBuilder(URI.create(
                    "https://oauth2.googleapis.com/tokeninfo?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8)))
                    .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(info.statusCode()).as(info.body()).isEqualTo(200);
            return info.body().replaceAll("\\s", "");
        }
    }

    @Test
    void servicesActAsTheImpersonatedServiceAccount() throws Exception {
        try (Halyx halyx = halyx(Credentials.impersonate(TARGET).lifetime(Duration.ofMinutes(15)))) {
            BlobStore reports = halyx.provider("gcp").blobStore(BUCKET);
            BlobKey key = BlobKey.of("logins/" + RUN + ".txt");

            reports.put(key, BlobContent.ofString("impersonated"));

            assertThat(reports.getBytes(key)).asString().isEqualTo("impersonated");
            reports.delete(key);
            assertThat(tokenInfo(reports)).contains("\"azp\":\"" + TARGET_ID + "\"");
        }
    }

    @Test
    void anAccountItMayNotActAsIsRefused() {
        try (Halyx halyx = halyx(Credentials.impersonate(DENIED))) {
            BlobStore reports = halyx.provider("gcp").blobStore(BUCKET);

            assertThatThrownBy(() -> reports.exists(BlobKey.of("any"))).satisfies(error -> {
                List<String> messages = new ArrayList<>();
                for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                    messages.add(String.valueOf(cause.getMessage()));
                }
                assertThat(String.join("\n", messages)).contains("iam.serviceAccounts.getAccessToken");
            });
        }
    }

    @Test
    void theDefaultChainActsAsTheTestIdentity() throws Exception {
        try (Halyx halyx = halyx(Credentials.defaultChain())) {
            assertThat(tokenInfo(halyx.provider("gcp").blobStore(BUCKET))).contains("\"azp\":").doesNotContain(TARGET_ID);
        }
    }
}
