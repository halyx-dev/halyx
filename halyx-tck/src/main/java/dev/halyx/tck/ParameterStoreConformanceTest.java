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
package dev.halyx.tck;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import dev.halyx.CloudContext;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.UnavailableException;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterCapability;
import dev.halyx.parameters.ParameterChanges;
import dev.halyx.parameters.ParameterKind;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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

    /**
     * How long a write may take to show in listings ({@code getAll}, and so {@code watch}). Zero for most
     * backends; AWS Secrets Manager documents its listing as eventually consistent.
     */
    protected Duration listingLag() {
        return Duration.ZERO;
    }

    /**
     * How long a delete may take to finish. Zero for most backends; AWS Secrets Manager deletes
     * asynchronously. Until it finishes, reads may still return the value, and writing the name fails with
     * the retryable {@link UnavailableException}. The store doesn't wait; callers that reuse names retry.
     */
    protected Duration deleteLag() {
        return Duration.ZERO;
    }

    /** Waits up to {@link #deleteLag()} for {@code name} to read as absent. */
    protected boolean gone(ParameterStore store, String name) {
        long deadline = System.nanoTime() + deleteLag().toNanos();
        while (store.get(name).isPresent()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            pause();
        }
        return true;
    }

    /** {@code put}, retrying {@link UnavailableException} for up to {@link #deleteLag()}. */
    protected Parameter putAfterDelete(ParameterStore store, String name, String value) {
        long deadline = System.nanoTime() + deleteLag().toNanos();
        while (true) {
            try {
                return store.put(name, value);
            } catch (UnavailableException e) {
                if (System.nanoTime() >= deadline) {
                    throw e;
                }
                pause();
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** {@code getAll(prefix)} once it lists exactly {@code expected}, waiting up to {@link #listingLag()}. */
    protected Map<String, Parameter> listed(ParameterStore store, String prefix, String... expected) {
        long deadline = System.nanoTime() + listingLag().toNanos();
        Map<String, Parameter> found = store.getAll(prefix);
        while (!found.keySet().equals(Set.of(expected)) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            found = store.getAll(prefix);
        }
        return found;
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

        Map<String, Parameter> db = listed(store(), "db", "db/url", "db/pool/size");
        assertThat(db).containsOnlyKeys("db/url", "db/pool/size");
        assertThat(db.get("db/pool/size").value()).isEqualTo("8");
        assertThat(listed(store(), "", "db/url", "db/pool/size", "cache/ttl"))
                .containsOnlyKeys("db/url", "db/pool/size", "cache/ttl");
    }

    @Test
    void getAllDoesNotMatchPartialSegments() {
        store().put("db/url", "u");
        store().put("dbx/url", "other");

        listed(store(), "", "db/url", "dbx/url");
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

        assertThat(gone(store(), "temp")).as("deleted").isTrue();
    }

    @Test
    void deletedNamesCanBeReused() {
        store().put("reused", "a");
        store().delete("reused");
        putAfterDelete(store(), "reused", "b");

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

    // --- JSON secrets --------------------------------------------------------------------------

    @Test
    void jsonFieldsCanBeRead() {
        store().put("db/creds", "{\"username\":\"app\",\"password\":\"pw\",\"port\":5432,\"replica\":{\"host\":\"r1\"}}");

        Parameter creds = store().get("db/creds").orElseThrow();
        assertThat(creds.field("password")).hasValue("pw");
        assertThat(creds.field("replica.host")).hasValue("r1");
        assertThat(creds.fields()).containsEntry("port", "5432").containsEntry("username", "app");
        assertThat(store().require("db/creds", "username")).isEqualTo("app");
        assertThatThrownBy(() -> store().require("db/creds", "missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void plainValuesHaveNoFields() {
        store().put("plain", "not json");

        assertThat(store().get("plain").orElseThrow().fields()).isEmpty();
    }

    // --- native names --------------------------------------------------------------------------

    /** A native prefix for this backend under the test's unique prefix, separator included. */
    protected String nativePrefix(String unique) {
        return unique + "/";
    }

    /** A name the portable rules reject but this backend accepts. */
    protected String nativeName() {
        return "db_password";
    }

    @Test
    void nativeNamesAreUsedAsTheyAre() {
        String nativePrefix = nativePrefix(prefix);
        ParameterStore nativeStore = context().parameterStore(config().prefix(nativePrefix).nativeNames(true).build());
        assertThatThrownBy(() -> store().put(nativeName(), "v")).isInstanceOf(IllegalArgumentException.class);
        try {
            nativeStore.put(nativeName(), "native-value");

            assertThat(nativeStore.require(nativeName())).isEqualTo("native-value");
            assertThat(listed(nativeStore, "", nativeName())).containsOnlyKeys(nativeName());
            ParameterStore unprefixed = context().parameterStore(config().nativeNames(true).build());
            assertThat(unprefixed.require(nativePrefix + nativeName())).isEqualTo("native-value");
        } finally {
            nativeStore.delete(nativeName());
        }
        assertThat(gone(nativeStore, nativeName())).as("deleted").isTrue();
    }

    // --- watching ------------------------------------------------------------------------------

    @Test
    void watchReportsAddedUpdatedAndRemovedValues() throws Exception {
        store().put("watched/a", "1");
        listed(store(), "watched", "watched/a");
        BlockingQueue<ParameterChanges> events = new LinkedBlockingQueue<>();
        try (AutoCloseable watch = store().watch("watched", Duration.ofMillis(200), events::add)) {
            store().put("watched/b", "2");
            assertThat(poll(events).added()).containsOnlyKeys("watched/b");

            store().put("watched/a", "1-rotated");
            ParameterChanges rotated = poll(events);
            assertThat(rotated.updated()).containsOnlyKeys("watched/a");
            assertThat(rotated.updated().get("watched/a").value()).isEqualTo("1-rotated");

            store().delete("watched/b");
            assertThat(poll(events).removed()).containsExactly("watched/b");
        }
        assertThat(store().statistics().changesDetected()).isZero(); // counted on the watching instance
    }

    private ParameterChanges poll(BlockingQueue<ParameterChanges> events) throws InterruptedException {
        ParameterChanges changes = events.poll(15 + listingLag().toSeconds(), TimeUnit.SECONDS);
        assertThat(changes).as("change notification").isNotNull();
        return changes;
    }

    @Test
    void watchedChangesInvalidateTheCache() throws Exception {
        ParameterStore cached = cachingStore(Duration.ofMinutes(5));
        store().put("cfg/flag", "off");
        listed(store(), "cfg", "cfg/flag");
        assertThat(cached.require("cfg/flag")).isEqualTo("off");
        BlockingQueue<ParameterChanges> events = new LinkedBlockingQueue<>();
        try (AutoCloseable watch = cached.watch("cfg", Duration.ofMillis(200), events::add)) {
            store().put("cfg/flag", "on");
            poll(events);

            assertThat(cached.require("cfg/flag")).isEqualTo("on");
            assertThat(cached.statistics().changesDetected()).isEqualTo(1);
        }
    }

    @Test
    void statisticsCountCacheHits() {
        ParameterStore cached = cachingStore(Duration.ofMinutes(5));
        store().put("counted", "v");
        cached.get("counted");
        cached.get("counted");
        cached.get("counted");

        assertThat(cached.statistics().backendReads()).isEqualTo(1);
        assertThat(cached.statistics().cacheHits()).isEqualTo(2);
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
