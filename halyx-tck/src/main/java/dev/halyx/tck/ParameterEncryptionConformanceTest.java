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

import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.KeyStoreConfig;
import dev.halyx.keys.SealedData;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.parameters.EncryptedParameterStore;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.time.Duration;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What encrypting a parameter store adds, beyond the parameter kit run through the view: what is stored, tampering,
 * the binding to the full name, wrong keys, values stored in the clear, a key per name, key service calls and the cache,
 * listings, and the size check. Keys come from a memory key store, so emulators need no key service; a subclass can use
 * its provider's own keys with {@link #keys()}.
 */
public abstract class ParameterEncryptionConformanceTest {

    private Halyx memory;
    private KeyStore keys;
    private String prefix;

    protected abstract CloudContext context();

    /** The store's configuration, without a prefix; the kit adds one per test. */
    protected abstract ParameterStoreConfig.Builder config();

    /**
     * The key store the views seal with, holding encryption keys {@code parameters}, {@code tenant-a} and
     * {@code tenant-b}. A memory store by default, at its own location, so its key IDs differ from those of another
     * memory store's keys of the same names.
     */
    protected KeyStore keys() {
        if (keys == null) {
            memory = Halyx.builder().register("tck-keys", ProviderConfig.of("memory")).build();
            keys = memory.provider("tck-keys").keyStore(KeyStoreConfig.builder().location("tck-parameters").build());
            InMemoryKeys created = keys.unwrap(InMemoryKeys.class);
            for (String name : new String[] {"parameters", "tenant-a", "tenant-b"}) {
                created.create(name, KeyPurpose.ENCRYPTION);
            }
        }
        return keys;
    }

    /** The largest value the backend stores, as its driver declares; empty skips the size check. */
    protected OptionalInt maxValueBytes() {
        return OptionalInt.empty();
    }

    /** How long a listing may take to show a write, as for the parameter kit. */
    protected Duration listingLag() {
        return Duration.ZERO;
    }

    /** Test names to skip because the environment doesn't implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newPrefix(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        prefix = "tck-enc-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        try {
            ParameterStore plain = plain();
            plain.getAll("").keySet().forEach(plain::delete);
        } catch (RuntimeException e) {
            // best effort: the prefix is unique
        }
        if (memory != null) {
            memory.close();
            memory = null;
            keys = null;
        }
    }

    private ParameterStore plain() {
        return context().parameterStore(config().prefix(prefix).build());
    }

    private CryptoKey parameters() {
        return keys().key("parameters");
    }

    private EncryptedParameterStore sealed() {
        return plain().encryptedWith(parameters());
    }

    private static String keyId(CryptoKey key) {
        return key.encrypt(new byte[0], new byte[0]).keys().getFirst().keyId();
    }

    /** The listing of {@code store} below {@code below}, once it shows {@code expected} names. */
    private Map<String, Parameter> listed(ParameterStore store, String below, int expected) {
        long deadline = System.nanoTime() + listingLag().toNanos();
        while (true) {
            store.refresh();
            Map<String, Parameter> found = store.getAll(below);
            if (found.size() >= expected || System.nanoTime() - deadline > 0) {
                return found;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return found;
            }
        }
    }

    // What is stored

    @Test
    void theStoreHoldsSealedTextAndTheViewReadsThePlaintext() {
        Parameter stored = sealed().put("db/password", "s3cret");

        assertThat(stored.value()).as("put returns the value as given").isEqualTo("s3cret");
        assertThat(stored.version()).isEqualTo(plain().get("db/password").orElseThrow().version());
        String raw = plain().require("db/password");
        assertThat(raw).startsWith("hlxs1:").doesNotContain("s3cret");
        assertThat(SealedData.parseText(raw).keys()).singleElement()
                .satisfies(entry -> assertThat(entry.keyId()).isEqualTo(keyId(parameters())));
        assertThat(sealed().require("db/password")).isEqualTo("s3cret");
    }

    @Test
    void specialCharactersAndJsonFieldsSurviveSealing() {
        String pem = "-----BEGIN KEY-----\nAbC+/=\n  äöü ✓\n-----END KEY-----";
        sealed().put("tls/key", pem);
        sealed().put("db/creds", "{\"username\":\"orders\",\"password\":\"p@ss\"}");

        assertThat(sealed().require("tls/key")).isEqualTo(pem);
        assertThat(sealed().require("db/creds", "password")).isEqualTo("p@ss");
    }

    // The binding to the full name

    @Test
    void aValueIsBoundToItsFullName() {
        sealed().put("db/password", "s3cret");

        EncryptedParameterStore below = context().parameterStore(config().prefix(prefix + "/db").build())
                .encryptedWith(parameters());
        assertThat(below.require("password")).as("the same parameter through a longer prefix").isEqualTo("s3cret");

        plain().put("db/moved", plain().require("db/password"));
        assertThatThrownBy(() -> sealed().require("db/moved")).isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void aChangedValueIsRefused() {
        sealed().put("db/password", "s3cret");
        byte[] stored = SealedData.parseText(plain().require("db/password")).toBytes();
        stored[stored.length - 1] ^= 1;
        plain().put("db/password", SealedData.parse(stored).toText());

        assertThatThrownBy(() -> sealed().require("db/password")).isInstanceOf(DataIntegrityException.class)
                .hasMessageNotContaining("s3cret");
        assertThatThrownBy(() -> sealed().async().require("db/password").join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(DataIntegrityException.class);
    }

    @Test
    void aKeyFromAnotherStoreCantOpenIt() {
        sealed().put("db/password", "s3cret");
        try (Halyx other = Halyx.builder().register("other", ProviderConfig.of("memory")).build()) {
            KeyStore elsewhere = other.provider("other").keyStore();
            elsewhere.unwrap(InMemoryKeys.class).create("parameters", KeyPurpose.ENCRYPTION);

            assertThatThrownBy(() -> plain().encryptedWith(elsewhere.key("parameters")).require("db/password"))
                    .isInstanceOf(InvalidArgumentException.class);
        }
    }

    // Values stored in the clear

    @Test
    void plaintextIsRefusedUnlessAllowed() {
        plain().put("legacy", "in the clear");

        assertThatThrownBy(() -> sealed().require("legacy")).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("isn't encrypted").hasMessageNotContaining("in the clear");
        assertThatThrownBy(() -> sealed().async().require("legacy").join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(InvalidArgumentException.class);
        assertThat(sealed().allowPlaintext().require("legacy")).isEqualTo("in the clear");
    }

    @Test
    void aStoreMovesToEncryptionOneValueAtATime() {
        plain().put("app/a", "one");
        plain().put("app/b", "two");
        EncryptedParameterStore migrating = sealed().allowPlaintext();

        migrating.put("app/a", migrating.require("app/a"));

        assertThat(plain().require("app/a")).startsWith("hlxs1:");
        assertThat(plain().require("app/b")).isEqualTo("two");
        Map<String, Parameter> all = listed(migrating, "app", 2);
        assertThat(all).containsOnlyKeys("app/a", "app/b");
        assertThat(all.get("app/a").value()).isEqualTo("one");
        assertThat(all.get("app/b").value()).isEqualTo("two");
    }

    // Keys

    @Test
    void aKeyPerNameAndAnyKeyOfTheStoreOpensWhatAnotherSealed() {
        EncryptedParameterStore byTenant = plain().encryptedWith(keys(), name -> "tenant-" + name.substring(0, 1));
        byTenant.put("a/db", "alpha");
        byTenant.put("b/db", "beta");

        assertThat(SealedData.parseText(plain().require("a/db")).keys().getFirst().keyId())
                .isEqualTo(keyId(keys().key("tenant-a")));
        assertThat(SealedData.parseText(plain().require("b/db")).keys().getFirst().keyId())
                .isEqualTo(keyId(keys().key("tenant-b")));
        assertThat(byTenant.require("a/db")).isEqualTo("alpha");
        assertThat(sealed().require("b/db")).isEqualTo("beta");
    }

    @Test
    void aCachedReadMakesNoKeyServiceCall() {
        EncryptedParameterStore cached = context().parameterStore(config().prefix(prefix)
                .cacheTtl(Duration.ofMinutes(5)).build()).encryptedWith(parameters());
        long wraps = keys().statistics().wraps();
        cached.put("db/password", "s3cret");
        assertThat(keys().statistics().wraps() - wraps).as("one wrap per put").isEqualTo(1);

        long unwraps = keys().statistics().unwraps();
        assertThat(cached.require("db/password")).isEqualTo("s3cret");
        assertThat(cached.require("db/password")).isEqualTo("s3cret");
        assertThat(cached.async().require("db/password").join()).isEqualTo("s3cret");

        assertThat(keys().statistics().unwraps() - unwraps).as("one unwrap, then cache hits").isEqualTo(1);
    }

    // Listings and asynchronous calls

    @Test
    void listingsOpenEveryValue() {
        for (int i = 0; i < 20; i++) {
            sealed().put(String.format("items/%02d", i), "value-" + i);
        }

        Map<String, Parameter> all = listed(sealed(), "items", 20);

        assertThat(all).hasSize(20);
        all.forEach((name, p) -> assertThat(p.value()).isEqualTo("value-" + Integer.parseInt(name.substring(6))));
        assertThat(sealed().async().getAll("items").join()).isEqualTo(all);
    }

    @Test
    void asyncCallsSealAndOpen() {
        Parameter stored = sealed().async().put("api/token", "t0k3n").join();

        assertThat(stored.value()).isEqualTo("t0k3n");
        assertThat(plain().require("api/token")).startsWith("hlxs1:");
        assertThat(sealed().async().require("api/token").join()).isEqualTo("t0k3n");
        assertThat(sealed().async().get("api/token").join()).get().extracting(Parameter::value).isEqualTo("t0k3n");
    }

    // Size

    @Test
    void aSealedValueTooLongForTheBackendIsRefusedBeforeItIsSent() {
        assumeTrue(maxValueBytes().isPresent(), "the backend has no fixed value size");
        int max = maxValueBytes().getAsInt();
        String value = "x".repeat(max * 3 / 4);

        assertThatThrownBy(() -> sealed().put("too-long", value)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("at most " + max + " bytes");
        assertThatThrownBy(() -> sealed().async().put("too-long", value).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(InvalidArgumentException.class);
        assertThat(plain().get("too-long")).as("nothing was sent").isEmpty();
    }
}
