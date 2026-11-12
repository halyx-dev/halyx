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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import com.google.cloud.parametermanager.v1.ParameterManagerClient;
import com.google.cloud.parametermanager.v1.ParameterVersionName;
import dev.halyx.gcp.parameters.ParameterManagerDriver;
import dev.halyx.gcp.parameters.ParameterManagerDriverAccess;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.tck.ParameterServerSideKeyConformanceTest;
import dev.halyx.tck.ParameterStoreConformanceTest;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The parameter store TCK on Parameter Manager, through the real client and gRPC stubs against
 * {@link FakeParameterManager}. To verify against GCP, run the same TCK with a real project.
 */
class ParameterManagerConformanceTest {

    private static FakeParameterManager fake;
    private static Halyx halyx;

    @BeforeAll
    static void start() throws Exception {
        fake = new FakeParameterManager();
        halyx = Halyx.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(URI.create("http://localhost:" + fake.port()))
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "test-project"))
                .build();
    }

    @AfterAll
    static void stop() {
        halyx.close();
        fake.close();
    }

    @Nested
    class Global extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }

    @Nested
    class Regional extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().location("europe-west2");
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }

    @Test
    void latestIsTheNewestVersionEvenWithForeignIds() {
        ParameterStore store = halyx.provider("gcp").parameterStore(ParameterStoreConfig.parameters().build());
        store.put("app/mode", "from-halyx");
        // Another tool writes a version whose id sorts before ours but is newer.
        fake.addForeignVersion("projects/test-project/locations/global/parameters/app__mode", "aaa-manual", "from-console");

        assertThat(store.require("app/mode")).isEqualTo("from-console");
        store.delete("app/mode");
    }

    @Test
    void renderResolvesSecretReferences() {
        ParameterStore raw = halyx.provider("gcp").parameterStore(ParameterStoreConfig.parameters().prefix("render").build());
        ParameterStore rendered = halyx.provider("gcp").parameterStore(ParameterStoreConfig.parameters().prefix("render")
                .property(ParameterManagerDriver.RENDER, "true").build());
        String reference = "password=__REF__(//secretmanager.googleapis.com/projects/test-project/secrets/db/versions/1)";
        raw.put("db", reference);

        assertThat(raw.require("db")).isEqualTo(reference);
        assertThat(rendered.require("db")).isEqualTo("password=" + FakeParameterManager.RENDERED_SECRET);
        raw.delete("db");
    }

    @Test
    void parameterIdsUseDoubleUnderscoreSeparators() {
        ParameterStore store = halyx.provider("gcp").parameterStore(ParameterStoreConfig.parameters().build());
        store.put("orders/db/url", "jdbc:x");

        var client = store.unwrap(ParameterManagerClient.class);
        assertThat(client.getParameterVersion(ParameterVersionName.of(
                "test-project", "global", "orders__db__url", store.get("orders/db/url").orElseThrow().version()))
                .getPayload().getData().toStringUtf8()).isEqualTo("jdbc:x");
        store.delete("orders/db/url");
    }

    @Test
    void versionIdsSortByCreationTime() {
        String earlier = ParameterManagerDriverAccess.newVersionId(Instant.parse("2026-01-01T00:00:00Z"));
        String later = ParameterManagerDriverAccess.newVersionId(Instant.parse("2026-01-01T00:00:01Z"));

        assertThat(earlier).matches("v\\d{13}-[0-9a-f]{4}").isLessThan(later);
    }

    /** A key in a key ring in {@code location}, served by the fake. */
    private static CryptoKey key(String location) {
        return halyx.provider("gcp").keyStore(KeyStoreConfig.builder()
                .location("projects/test-project/locations/" + location + "/keyRings/parameters").build()).key("values");
    }

    @Nested
    class KeyedRegional extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return halyx.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().location("europe-west2").serverSideKey(key("europe-west2"));
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }

    @Nested
    class ServerSideKeys extends ParameterServerSideKeyConformanceTest {
        private final CryptoKey key = key("global");

        @Override
        protected CloudContext context() {
            return halyx.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected CryptoKey serverSideKey() {
            return key;
        }

        @Override
        protected Optional<String> storedServerSideKey(ParameterStore store, String name) {
            return fake.kmsKey("projects/test-project/locations/global/parameters/" + name.replace("/", "__"));
        }
    }

    @Test
    void theKeyMustBeInTheStoresLocation() {
        ParameterStore store = halyx.provider("gcp").parameterStore(ParameterStoreConfig.parameters()
                .location("europe-west2").prefix("sse-location").serverSideKey(key("global")).build());

        assertThatThrownBy(() -> store.put("app/mode", "x")).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("'europe-west2'").hasMessageContaining("'global'");
        assertThat(store.get("app/mode")).isEmpty();
    }
}
