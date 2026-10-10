package org.kamranzafar.stratum.tck;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.kamranzafar.stratum.CloudContext;
import org.kamranzafar.stratum.error.NotFoundException;
import org.kamranzafar.stratum.parameters.Parameter;
import org.kamranzafar.stratum.parameters.ParameterCapability;
import org.kamranzafar.stratum.parameters.ParameterKind;
import org.kamranzafar.stratum.parameters.ParameterStore;
import org.kamranzafar.stratum.parameters.ParameterStoreConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for {@link ParameterStore}. Providers extend it once per kind they support. Each
 * test works under a fresh random prefix and deletes what it wrote.
 */
public abstract class ParameterStoreConformanceTest {

    private String prefix;
    private final List<ParameterStore> created = new ArrayList<>();

    protected abstract CloudContext context();

    /** The store configuration under test; the TCK sets prefix and cache settings. */
    protected abstract ParameterStoreConfig.Builder config();

    /** Test names to skip in this environment, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newPrefix(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        prefix = "tck-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        ParameterStore store = context().parameterStore(config().prefix(prefix).build());
        store.getAll("").keySet().forEach(store::delete);
    }

    protected ParameterStore store() {
        return context().parameterStore(config().prefix(prefix).build());
    }

    private ParameterStore cachingStore(Duration ttl) {
        return context().parameterStore(config().prefix(prefix).cacheTtl(ttl).build());
    }

    @Test
    void putThenGet() {
        Parameter stored = store().put("db/url", "jdbc:postgresql://db:5432/orders");

        assertThat(stored.name()).isEqualTo("db/url");
        assertThat(stored.version()).isNotEmpty();
        Parameter read = store().get("db/url").orElseThrow();
        assertThat(read.name()).isEqualTo("db/url");
        assertThat(read.value()).isEqualTo("jdbc:postgresql://db:5432/orders");
        assertThat(read.version()).isEqualTo(stored.version());
        assertThat(store().require("db/url")).isEqualTo("jdbc:postgresql://db:5432/orders");
    }

    @Test
    void missingIsEmptyAndRequireFails() {
        assertThat(store().get("missing")).isEmpty();
        assertThatThrownBy(() -> store().require("missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void putReplacesWithANewVersion() {
        Parameter v1 = store().put("feature/limit", "10");
        Parameter v2 = store().put("feature/limit", "20");

        assertThat(v2.version()).isNotEqualTo(v1.version());
        assertThat(store().require("feature/limit")).isEqualTo("20");
    }

    @Test
    void earlierVersionsCanBeRead() {
        assumeTrue(store().capabilities().supports(ParameterCapability.VERSIONS));
        Parameter v1 = store().put("rotating", "first");
        store().put("rotating", "second");

        assertThat(store().get("rotating", v1.version())).hasValueSatisfying(p -> assertThat(p.value()).isEqualTo("first"));
    }

    @Test
    void getAllReturnsEverythingBelowAPrefix() {
        store().put("db/url", "u");
        store().put("db/pool/size", "8");
        store().put("cache/ttl", "60");

        Map<String, Parameter> db = store().getAll("db");
        assertThat(db).containsOnlyKeys("db/url", "db/pool/size");
        assertThat(db.get("db/pool/size").value()).isEqualTo("8");
        assertThat(store().getAll("")).containsOnlyKeys("db/url", "db/pool/size", "cache/ttl");
    }

    @Test
    void getAllDoesNotMatchPartialSegments() {
        store().put("db/url", "u");
        store().put("dbx/url", "other");

        assertThat(store().getAll("db")).containsOnlyKeys("db/url");
    }

    @Test
    void storesAreIsolatedByPrefix() {
        store().put("shared", "mine");
        ParameterStore other = context().parameterStore(config().prefix(prefix + "-other").build());

        assertThat(other.get("shared")).isEmpty();
        assertThat(other.getAll("")).isEmpty();
    }

    @Test
    void deleteIsIdempotent() {
        store().put("temp", "x");
        store().delete("temp");
        store().delete("temp");

        assertThat(store().get("temp")).isEmpty();
    }

    @Test
    void deletedNamesCanBeReused() {
        store().put("reused", "a");
        store().delete("reused");
        store().put("reused", "b");

        assertThat(store().require("reused")).isEqualTo("b");
    }

    @Test
    void valuesKeepSpecialCharactersAndLines() {
        String pem = "-----BEGIN KEY-----\nAbC+/=\n  äöü ✓ {\"json\": true}\n-----END KEY-----";
        store().put("tls/key", pem);

        assertThat(store().require("tls/key")).isEqualTo(pem);
    }

    @Test
    void largeValues() {
        String large = "x".repeat(3500);
        store().put("large", large);

        assertThat(store().require("large")).isEqualTo(large);
    }

    @Test
    void typedReads() {
        store().put("pool/size", "16");

        assertThat(store().get("pool/size", Integer::valueOf)).hasValue(16);
    }

    @Test
    void cachedReadsAreServedUntilRefresh() {
        ParameterStore cached = cachingStore(Duration.ofMinutes(5));
        store().put("cached", "v1");
        assertThat(cached.require("cached")).isEqualTo("v1");

        store().put("cached", "v2");
        assertThat(cached.require("cached")).as("served from cache").isEqualTo("v1");

        cached.refresh();
        assertThat(cached.require("cached")).isEqualTo("v2");
    }

    @Test
    void writesInvalidateTheirOwnCache() {
        ParameterStore cached = cachingStore(Duration.ofMinutes(5));
        cached.put("own", "v1");
        cached.require("own");
        cached.put("own", "v2");

        assertThat(cached.require("own")).isEqualTo("v2");
    }

    @Test
    void invalidNamesAreRejected() {
        for (String bad : List.of("", "/leading", "trailing/", "double//slash", "under_score", "dot.ted", "--", "a--b", "-a")) {
            assertThatThrownBy(() -> store().put(bad, "v")).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void reportsKindAndProvider() {
        assertThat(store().kind()).isIn(ParameterKind.PARAMETERS, ParameterKind.SECRETS);
        assertThat(store().provider()).isEqualTo(context().providerId());
    }
}
