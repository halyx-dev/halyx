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
package dev.halyx.parameters;

import dev.halyx.Halyx;
import dev.halyx.capability.Capabilities;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.CloudException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.memory.InMemoryParameters;
import dev.halyx.parameters.spi.ParameterDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParameterServerSideKeyTest {

    private final List<String> keyCalls = new CopyOnWriteArrayList<>();
    private Halyx halyx;
    private KeyStore keys;

    @BeforeEach
    void start() {
        halyx = Halyx.builder()
                .register("test", ProviderConfig.of("memory"))
                .interceptor(new Interceptor() {
                    @Override
                    public <T> T intercept(OperationContext context, Invocation<T> next) {
                        if (context.service().equals("keys")) {
                            keyCalls.add(context.operation());
                        }
                        return next.proceed();
                    }
                })
                .build();
        keys = halyx.provider("test").keyStore();
        keys.unwrap(InMemoryKeys.class).create("parameters", KeyPurpose.ENCRYPTION);
        keys.unwrap(InMemoryKeys.class).create("mac", KeyPurpose.MAC);
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    @Test
    void creatingAStoreMakesNoKeyCallAndTheFirstPutReadsTheKeyOnce() {
        CryptoKey key = keys.key("parameters");
        keyCalls.clear();

        ParameterStore store = halyx.provider("test").parameterStore(
                ParameterStoreConfig.secrets().prefix("app").serverSideKey(key).build());
        assertThat(keyCalls).as("no key service call at creation").isEmpty();

        store.put("db/password", "one");
        store.put("db/password", "two");
        store.async().put("api/token", "t").join();

        assertThat(keyCalls).as("the key's metadata, read once and cached").hasSize(1);
        assertThat(store.unwrap(InMemoryParameters.class).serverSideKey("app/db/password"))
                .contains(key.metadata().nativeId());
        assertThat(store.unwrap(InMemoryParameters.class).serverSideKey("app/api/token"))
                .contains(key.metadata().nativeId());
    }

    @Test
    void aKeyThatCantEncryptIsRefusedOnTheFirstPut() {
        ParameterStore store = halyx.provider("test").parameterStore(
                ParameterStoreConfig.secrets().serverSideKey(keys.key("mac")).build());

        assertThatThrownBy(() -> store.put("db/password", "x"))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("can't encrypt");
        assertThatThrownBy(() -> store.async().put("db/password", "x").join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(InvalidArgumentException.class);
        assertThat(store.get("db/password")).isEmpty();
    }

    @Test
    void aStoreWithoutTheCapabilityRefusesTheKeyWhenCreated() {
        ParameterStoreConfig config = ParameterStoreConfig.secrets().serverSideKey(keys.key("parameters")).build();

        assertThatThrownBy(() -> new DefaultParameterStore("memory", config, new NoKeysDriver(), InterceptorChain.empty()))
                .isInstanceOf(UnsupportedFeatureException.class).hasMessageContaining("SERVER_SIDE_KMS");
    }

    @Test
    void aKeyOfAnotherProviderIsRefusedWhenCreated() {
        ParameterStoreConfig config = ParameterStoreConfig.secrets().serverSideKey(keys.key("parameters")).build();

        assertThatThrownBy(() -> new DefaultParameterStore("aws", config, new KeyedDriver(), InterceptorChain.empty()))
                .isInstanceOf(InvalidArgumentException.class).hasMessageContaining("is a memory key");
    }

    @Test
    void configurationsCompareTheirKeyByIdentity() {
        CryptoKey key = keys.key("parameters");
        ParameterStoreConfig a = ParameterStoreConfig.secrets().prefix("app").serverSideKey(key).build();

        assertThat(a).isEqualTo(ParameterStoreConfig.secrets().prefix("app").serverSideKey(key).build())
                .hasSameHashCodeAs(ParameterStoreConfig.secrets().prefix("app").serverSideKey(key).build())
                .isNotEqualTo(ParameterStoreConfig.secrets().prefix("app").build())
                .isNotEqualTo(ParameterStoreConfig.secrets().prefix("app").serverSideKey(keys.key("mac")).build());
        assertThat(a.serverSideKey()).containsSame(key);
        assertThat(ParameterStoreConfig.secrets().build().serverSideKey()).isEmpty();
        assertThat(a.toString()).contains("serverSideKey=parameters");
        assertThatThrownBy(() -> ParameterStoreConfig.secrets().serverSideKey(null)).isInstanceOf(NullPointerException.class);
    }

    /** A driver without {@code SERVER_SIDE_KMS}. */
    private static class NoKeysDriver implements ParameterDriver {
        @Override
        public Optional<Parameter> get(String name) {
            return Optional.empty();
        }

        @Override
        public Optional<Parameter> get(String name, String version) {
            return Optional.empty();
        }

        @Override
        public Map<String, Parameter> list(String prefix) {
            return Map.of();
        }

        @Override
        public Parameter put(String name, String value) {
            return new Parameter(name, value, "1", null);
        }

        @Override
        public void delete(String name) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.none();
        }

        @Override
        public CloudException translate(Throwable error) {
            return new UnknownCloudException(error.getMessage(), error, "memory", null);
        }

        @Override
        public <T> T unwrap(Class<T> nativeType) {
            throw new IllegalArgumentException();
        }
    }

    /** A driver that declares {@code SERVER_SIDE_KMS}. */
    private static final class KeyedDriver extends NoKeysDriver {
        @Override
        public Capabilities capabilities() {
            return Capabilities.of(ParameterCapability.SERVER_SIDE_KMS);
        }
    }
}
