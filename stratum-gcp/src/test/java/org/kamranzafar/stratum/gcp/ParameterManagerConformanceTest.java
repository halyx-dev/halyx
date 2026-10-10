package org.kamranzafar.stratum.gcp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.config.Credentials;
import org.kamranzafar.stratum.config.ProviderConfig;
import com.google.cloud.parametermanager.v1.ParameterManagerClient;
import com.google.cloud.parametermanager.v1.ParameterVersionName;
import org.kamranzafar.stratum.gcp.parameters.ParameterManagerDriver;
import org.kamranzafar.stratum.gcp.parameters.ParameterManagerDriverAccess;
import org.kamranzafar.stratum.parameters.ParameterStore;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;
import org.kamranzafar.stratum.tck.ParameterStoreConformanceTest;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parameter store TCK on Parameter Manager, through the real client and gRPC stubs against
 * {@link FakeParameterManager}. To verify against GCP, run the same TCK with a real project.
 */
class ParameterManagerConformanceTest {

    private static FakeParameterManager fake;
    private static Stratum stratum;

    @BeforeAll
    static void start() throws Exception {
        fake = new FakeParameterManager();
        stratum = Stratum.builder()
                .register("gcp", ProviderConfig.of("gcp")
                        .endpoint(URI.create("http://localhost:" + fake.port()))
                        .credentials(Credentials.anonymous())
                        .property(GcpProperties.PROJECT_ID, "test-project"))
                .build();
    }

    @AfterAll
    static void stop() {
        stratum.close();
        fake.close();
    }

    @Nested
    class Global extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }
    }

    @Nested
    class Regional extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return stratum.provider("gcp");
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().location("europe-west2");
        }
    }

    @Test
    void latestIsTheNewestVersionEvenWithForeignIds() {
        ParameterStore store = stratum.provider("gcp").parameterStore(ParameterStoreConfig.parameters().build());
        store.put("app/mode", "from-stratum");
        // Another tool writes a version whose id sorts before ours but is newer.
        fake.addForeignVersion("projects/test-project/locations/global/parameters/app__mode", "aaa-manual", "from-console");

        assertThat(store.require("app/mode")).isEqualTo("from-console");
        store.delete("app/mode");
    }

    @Test
    void renderResolvesSecretReferences() {
        ParameterStore raw = stratum.provider("gcp").parameterStore(ParameterStoreConfig.parameters().prefix("render").build());
        ParameterStore rendered = stratum.provider("gcp").parameterStore(ParameterStoreConfig.parameters().prefix("render")
                .property(ParameterManagerDriver.RENDER, "true").build());
        String reference = "password=__REF__(//secretmanager.googleapis.com/projects/test-project/secrets/db/versions/1)";
        raw.put("db", reference);

        assertThat(raw.require("db")).isEqualTo(reference);
        assertThat(rendered.require("db")).isEqualTo("password=" + FakeParameterManager.RENDERED_SECRET);
        raw.delete("db");
    }

    @Test
    void parameterIdsUseDoubleUnderscoreSeparators() {
        ParameterStore store = stratum.provider("gcp").parameterStore(ParameterStoreConfig.parameters().build());
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
