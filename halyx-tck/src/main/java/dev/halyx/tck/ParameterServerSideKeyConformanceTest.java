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
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.parameters.Parameter;
import dev.halyx.parameters.ParameterCapability;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.parameters.ParameterStoreConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Server-side encryption with a key from the provider's key service ({@code SERVER_SIDE_KMS}): parameters created
 * through a store configured with {@code serverSideKey} are encrypted at rest with that key, as the provider's own API
 * reports it, and reads need nothing.
 */
public abstract class ParameterServerSideKeyConformanceTest {

    private String prefix;

    protected abstract CloudContext context();

    /** The store configuration under test, without a key; the kit sets the prefix and the key. */
    protected abstract ParameterStoreConfig.Builder config();

    /** An encryption key of the store's provider that its parameter service can use. */
    protected abstract CryptoKey serverSideKey();

    /**
     * The key the provider reports the parameter is encrypted with, read through its own API (SSM {@code KeyId},
     * Secrets Manager {@code KmsKeyId}, Secret Manager {@code kmsKeyName}, Parameter Manager {@code kms_key}); empty for
     * the service's default.
     *
     * @param store a plain store over the same values, to reach the native client
     * @param name  the full portable name, the store's prefix included
     */
    protected abstract Optional<String> storedServerSideKey(ParameterStore store, String name);

    /** What {@link #storedServerSideKey} reports for {@link #serverSideKey()}: its native id unless overridden. */
    protected String expectedServerSideKey() {
        return serverSideKey().metadata().nativeId();
    }

    /**
     * Whether the environment reports the key a parameter is stored with. Emulators that accept the key without
     * recording it return false: every write still runs with the key and its value is checked, and the real cloud tier
     * checks the key.
     */
    protected boolean reportsServerSideKeys() {
        return true;
    }

    /**
     * Whether each put through a keyed store encrypts the new version with the key, even for a parameter created
     * without it (SSM). False where the key is fixed when the parameter is created.
     */
    protected boolean everyPutUsesTheKey() {
        return false;
    }

    /** Test names to skip because the environment doesn't implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newPrefix(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        assumeTrue(plain().capabilities().supports(ParameterCapability.SERVER_SIDE_KMS), "no server-side keys");
        prefix = "tck-sse-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void cleanUp() {
        if (prefix != null) {
            ParameterStore store = plain();
            store.getAll("").keySet().forEach(store::delete);
        }
    }

    private ParameterStore plain() {
        return context().parameterStore(config().prefix(prefix == null ? "" : prefix).build());
    }

    private ParameterStore keyed() {
        return context().parameterStore(config().prefix(prefix).serverSideKey(serverSideKey()).build());
    }

    private Optional<String> stored(String name) {
        return storedServerSideKey(plain(), prefix + "/" + name);
    }

    private void assertKeyed(String name) {
        if (reportsServerSideKeys()) {
            assertThat(stored(name)).as("the key %s is stored with", name).contains(expectedServerSideKey());
        }
    }

    private void assertNotKeyed(String name) {
        if (reportsServerSideKeys()) {
            assertThat(stored(name)).as("the service's default").isNotEqualTo(Optional.of(expectedServerSideKey()));
        }
    }

    @Test
    void newParametersAreEncryptedWithTheKey() {
        keyed().put("db/password", "s3cr3t");
        keyed().async().put("api/token", "t0k3n").join();

        assertKeyed("db/password");
        assertKeyed("api/token");
        assertThat(plain().require("db/password")).as("reads need nothing").isEqualTo("s3cr3t");
        assertThat(plain().require("api/token")).isEqualTo("t0k3n");
        assertThat(keyed().require("db/password")).isEqualTo("s3cr3t");
    }

    @Test
    void newValuesKeepTheKey() {
        Parameter first = keyed().put("db/password", "one");
        Parameter second = keyed().put("db/password", "two");

        assertThat(second.version()).isNotEqualTo(first.version());
        assertThat(keyed().require("db/password")).isEqualTo("two");
        assertKeyed("db/password");
    }

    @Test
    void aParameterCreatedWithoutTheKeyKeepsItsEncryptionUnlessEveryPutUsesTheKey() {
        plain().put("db/password", "one");
        keyed().put("db/password", "two");

        assertThat(plain().require("db/password")).isEqualTo("two");
        if (everyPutUsesTheKey()) {
            assertKeyed("db/password");
        } else {
            assertNotKeyed("db/password");
        }
    }

    @Test
    void plainStoresUseTheServicesDefault() {
        plain().put("db/password", "s3cr3t");

        assertNotKeyed("db/password");
    }

    @Test
    void storesWithTheSameKeyAreShared() {
        ParameterStoreConfig config = config().prefix(prefix).serverSideKey(serverSideKey()).build();

        assertThat(config.serverSideKey()).containsSame(serverSideKey());
        assertThat(context().parameterStore(config)).isSameAs(keyed()).isNotSameAs(plain());
    }

    @Test
    void aKeyFromAnotherProviderIsRefusedWhenTheStoreIsCreated() {
        assumeTrue(!plain().provider().equals("memory"), "the memory provider's keys are its own");
        try (Halyx memory = Halyx.builder().register("tck-keys", ProviderConfig.of("memory")).build()) {
            KeyStore keys = memory.provider("tck-keys").keyStore();
            keys.unwrap(InMemoryKeys.class).create("elsewhere", KeyPurpose.ENCRYPTION);
            CryptoKey foreign = keys.key("elsewhere");

            assertThatThrownBy(() -> context().parameterStore(config().prefix(prefix).serverSideKey(foreign).build()))
                    .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("memory key");
        }
    }
}
