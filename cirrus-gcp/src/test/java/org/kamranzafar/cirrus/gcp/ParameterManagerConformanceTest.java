package org.kamranzafar.cirrus.gcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import com.google.cloud.parametermanager.v1.ParameterManagerClient;
import com.google.cloud.parametermanager.v1.ParameterVersionName;
import org.kamranzafar.cirrus.gcp.parameters.ParameterManagerDriver;
import org.kamranzafar.cirrus.gcp.parameters.ParameterManagerDriverAccess;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.tck.ParameterStoreConformanceTest;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parameter store TCK on Parameter Manager, through the real client and gRPC stubs against
 * {@link FakeParameterManager}. To verify against GCP, run the same TCK with a real project.
 */
class ParameterManagerConformanceTest {

    private static FakeParameterManager fake;
    private static Cirrus cirrus;

    @BeforeAll
    static void start() throws Exception {
        fake = new FakeParameterManager();
        cirrus = Cirrus.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(URI.create("http://localhost:" + fake.port()))
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "test-project"))
                .build();
    }

    @AfterAll
    static void stop() {
        cirrus.close();
        fake.close();
    }

    @Nested
    class Global extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return cirrus.provider("gcp");
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
        protected CloudContext context() {
            return cirrus.provider("gcp");
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
        ParameterStore store = cirrus.provider("gcp").parameterStore(ParameterStoreConfig.parameters().build());
        store.put("app/mode", "from-cirrus");
        // Another tool writes a version whose id sorts before ours but is newer.
        fake.addForeignVersion("projects/test-project/locations/global/parameters/app__mode", "aaa-manual", "from-console");

        assertThat(store.require("app/mode")).isEqualTo("from-console");
        store.delete("app/mode");
    }

    @Test
    void renderResolvesSecretReferences() {
        ParameterStore raw = cirrus.provider("gcp").parameterStore(ParameterStoreConfig.parameters().prefix("render").build());
        ParameterStore rendered = cirrus.provider("gcp").parameterStore(ParameterStoreConfig.parameters().prefix("render")
                .property(ParameterManagerDriver.RENDER, "true").build());
        String reference = "password=__REF__(//secretmanager.googleapis.com/projects/test-project/secrets/db/versions/1)";
        raw.put("db", reference);

        assertThat(raw.require("db")).isEqualTo(reference);
        assertThat(rendered.require("db")).isEqualTo("password=" + FakeParameterManager.RENDERED_SECRET);
        raw.delete("db");
    }

    @Test
    void parameterIdsUseDoubleUnderscoreSeparators() {
        ParameterStore store = cirrus.provider("gcp").parameterStore(ParameterStoreConfig.parameters().build());
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
}
