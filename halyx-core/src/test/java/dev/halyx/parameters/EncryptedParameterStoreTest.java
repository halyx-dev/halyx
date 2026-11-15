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
import dev.halyx.error.DataIntegrityException;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.NotFoundException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.keys.CryptoKey;
import dev.halyx.keys.KeyPurpose;
import dev.halyx.keys.KeyStore;
import dev.halyx.keys.SealedData;
import dev.halyx.memory.InMemoryKeys;
import dev.halyx.parameters.spi.ParameterDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncryptedParameterStoreTest {

    private final List<String> keyCalls = new CopyOnWriteArrayList<>();
    private Halyx halyx;
    private KeyStore keys;
    private CryptoKey key;

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
        keys.unwrap(InMemoryKeys.class).create("config", KeyPurpose.ENCRYPTION);
        keys.unwrap(InMemoryKeys.class).create("tenant-a", KeyPurpose.ENCRYPTION);
        keys.unwrap(InMemoryKeys.class).create("tenant-b", KeyPurpose.ENCRYPTION);
        key = keys.key("config");
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    private ParameterStore store(String prefix) {
        return halyx.provider("test").parameterStore(ParameterStoreConfig.secrets().prefix(prefix).build());
    }

    private ParameterStore store(String prefix, Duration cacheTtl) {
        return halyx.provider("test").parameterStore(ParameterStoreConfig.secrets().prefix(prefix).cacheTtl(cacheTtl)
                .build());
    }

    @Test
    void valuesAreStoredSealedAndReadBackOpened() {
        ParameterStore plain = store("app");
        EncryptedParameterStore encrypted = plain.encryptedWith(key);

        Parameter stored = encrypted.put("db/password", "s3cret");

        assertThat(stored.name()).isEqualTo("db/password");
        assertThat(stored.value()).as("put returns the value as given").isEqualTo("s3cret");
        assertThat(plain.require("db/password")).startsWith("hlxs1:").doesNotContain("s3cret");
        assertThat(SealedData.parseText(plain.require("db/password")).keys())
                .singleElement().satisfies(entry -> assertThat(entry.keyId()).isEqualTo(keyId(key)));
        assertThat(encrypted.require("db/password")).isEqualTo("s3cret");
        assertThat(encrypted.get("db/password")).get().satisfies(p -> {
            assertThat(p.name()).isEqualTo("db/password");
            assertThat(p.value()).isEqualTo("s3cret");
            assertThat(p.version()).isEqualTo(plain.get("db/password").orElseThrow().version());
        });
        assertThat(encrypted.get("missing")).isEmpty();
        assertThatThrownBy(() -> encrypted.require("missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aValueIsBoundToItsFullNameWhateverThePrefixItsReadThrough() {
        ParameterStore app = store("app");
        app.encryptedWith(key).put("db/password", "s3cret");

        assertThat(store("").encryptedWith(key).require("app/db/password"))
                .as("the same parameter through a store without a prefix").isEqualTo("s3cret");

        app.put("db/copied", app.require("db/password"));
        assertThatThrownBy(() -> app.encryptedWith(key).require("db/copied"))
                .as("a sealed value moved to another name").isInstanceOf(DataIntegrityException.class);
    }

    @Test
    void requireWithAFieldOpensTheValueThenReadsTheField() {
        EncryptedParameterStore encrypted = store("app").encryptedWith(key);
        encrypted.put("db/creds", "{\"username\":\"orders\",\"password\":\"p@ss\",\"db\":{\"port\":5432}}");

        assertThat(encrypted.require("db/creds", "password")).isEqualTo("p@ss");
        assertThat(encrypted.require("db/creds", "db.port")).isEqualTo("5432");
        assertThat(encrypted.get("db/creds").orElseThrow().fields()).containsEntry("username", "orders");
        assertThatThrownBy(() -> encrypted.require("db/creds", "missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void plaintextIsRefusedUnlessAllowedAndWritesAreAlwaysSealed() {
        ParameterStore plain = store("app");
        plain.put("legacy", "in the clear");
        EncryptedParameterStore encrypted = plain.encryptedWith(key);

        assertThatThrownBy(() -> encrypted.get("legacy"))
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("app/legacy").hasMessageContaining("isn't encrypted")
                .hasMessageNotContaining("in the clear");
        assertThatThrownBy(() -> encrypted.getAll("")).isInstanceOf(InvalidArgumentException.class);
        assertThatThrownBy(() -> encrypted.async().get("legacy").join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(InvalidArgumentException.class);

        EncryptedParameterStore migrating = encrypted.allowPlaintext();
        assertThat(migrating.allowPlaintext()).isSameAs(migrating);
        assertThat(migrating.require("legacy")).isEqualTo("in the clear");
        migrating.put("legacy", "now sealed");
        assertThat(plain.require("legacy")).startsWith("hlxs1:");
        assertThat(encrypted.require("legacy")).isEqualTo("now sealed");
    }

    @Test
    void malformedSealedTextIsRefusedEvenWhenPlaintextIsAllowed() {
        ParameterStore plain = store("app");
        plain.put("broken", "hlxs1:not-really");

        assertThatThrownBy(() -> plain.encryptedWith(key).allowPlaintext().get("broken"))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void theCacheHoldsOpenedValuesSoACachedReadMakesNoKeyCall() {
        EncryptedParameterStore encrypted = store("app", Duration.ofMinutes(5)).encryptedWith(key);
        encrypted.put("db/password", "s3cret");
        encrypted.put("db/user", "orders");
        encrypted.require("db/password");
        encrypted.getAll("db");
        keyCalls.clear();

        assertThat(encrypted.require("db/password")).isEqualTo("s3cret");
        assertThat(encrypted.async().require("db/password").join()).isEqualTo("s3cret");
        assertThat(encrypted.getAll("db")).hasSize(2);
        assertThat(encrypted.async().getAll("db").join()).hasSize(2);

        assertThat(keyCalls).as("cache hits open nothing").isEmpty();
        assertThat(encrypted.statistics().cacheHits()).isEqualTo(4);

        encrypted.refresh();
        encrypted.require("db/password");
        assertThat(keyCalls).containsExactly("unwrap");
    }

    @Test
    void withoutACacheEveryReadUnwraps() {
        EncryptedParameterStore encrypted = store("app").encryptedWith(key);
        encrypted.put("db/password", "s3cret");
        keyCalls.clear();

        encrypted.require("db/password");
        encrypted.require("db/password");
        encrypted.async().require("db/password").join();

        assertThat(keyCalls).containsExactly("unwrap", "unwrap", "unwrap");
    }

    @Test
    void aWriteThroughTheViewDropsTheViewsCache() {
        EncryptedParameterStore encrypted = store("app", Duration.ofMinutes(5)).encryptedWith(key);
        encrypted.put("flag", "one");
        assertThat(encrypted.require("flag")).isEqualTo("one");

        encrypted.put("flag", "two");
        assertThat(encrypted.require("flag")).isEqualTo("two");
        encrypted.async().put("flag", "three").join();
        assertThat(encrypted.require("flag")).isEqualTo("three");
        encrypted.delete("flag");
        assertThat(encrypted.get("flag")).isEmpty();
    }

    @Test
    void listingsOpenEveryValueInOrder() {
        EncryptedParameterStore encrypted = store("app").encryptedWith(key);
        for (int i = 0; i < 40; i++) {
            encrypted.put(String.format("items/%02d", i), "value-" + i);
        }
        keyCalls.clear();

        Map<String, Parameter> all = encrypted.getAll("items");
        Map<String, Parameter> async = encrypted.async().getAll("items").join();

        assertThat(all).hasSize(40);
        assertThat(all.keySet()).first().isEqualTo("items/00");
        all.forEach((name, p) -> assertThat(p.value()).isEqualTo("value-" + Integer.parseInt(name.substring(6))));
        assertThat(async).isEqualTo(all);
        assertThat(keyCalls).as("one unwrap per value").hasSize(80).containsOnly("unwrap");
        assertThat(encrypted.getAll("nothing-here")).isEmpty();
    }

    @Test
    void earlierVersionsAreOpenedToo() {
        ParameterStore plain = store("app");
        EncryptedParameterStore encrypted = plain.encryptedWith(key);
        String first = encrypted.put("rotating", "v1").version();
        encrypted.put("rotating", "v2");

        assertThat(encrypted.get("rotating", first)).get().extracting(Parameter::value).isEqualTo("v1");
        assertThat(encrypted.async().get("rotating", first).join()).get().extracting(Parameter::value).isEqualTo("v1");
        assertThat(plain.get("rotating", first)).get().extracting(Parameter::value).asString().startsWith("hlxs1:");
    }

    @Test
    void theAsynchronousViewSealsAndOpensAndItsBlockingViewIsTheEncryptedOne() {
        EncryptedParameterStore encrypted = store("app").encryptedWith(key);

        Parameter stored = encrypted.async().put("api/token", "t0k3n").join();

        assertThat(stored.value()).isEqualTo("t0k3n");
        assertThat(store("app").require("api/token")).startsWith("hlxs1:");
        assertThat(encrypted.async().require("api/token").join()).isEqualTo("t0k3n");
        assertThat(encrypted.async().blocking()).isSameAs(encrypted);
        assertThat(encrypted.async(Runnable::run).blocking()).isSameAs(encrypted);
        assertThat(encrypted.async().kind()).isEqualTo(ParameterKind.SECRETS);
        encrypted.async().delete("api/token").join();
        assertThat(encrypted.get("api/token")).isEmpty();
    }

    @Test
    void aKeyPerNameAndAnyKeyOfTheStoreOpensWhatAnotherSealed() {
        ParameterStore plain = store("tenants");
        EncryptedParameterStore byTenant = plain.encryptedWith(keys, name -> "tenant-" + name.substring(0, 1));

        byTenant.put("a/db", "alpha");
        byTenant.put("b/db", "beta");

        assertThat(SealedData.parseText(plain.require("a/db")).keys().getFirst().keyId())
                .isEqualTo(keyId(keys.key("tenant-a")));
        assertThat(SealedData.parseText(plain.require("b/db")).keys().getFirst().keyId())
                .isEqualTo(keyId(keys.key("tenant-b")));
        assertThat(byTenant.require("a/db")).isEqualTo("alpha");
        assertThat(plain.encryptedWith(key).require("b/db")).as("opened by the key named in the data").isEqualTo("beta");
    }

    @Test
    void aSealedValueTooLongForTheBackendIsRefusedBeforeItIsSent() {
        LimitedDriver driver = new LimitedDriver(400);
        DefaultParameterStore store = new DefaultParameterStore("memory", ParameterStoreConfig.parameters().build(),
                driver, InterceptorChain.empty());
        EncryptedParameterStore encrypted = store.encryptedWith(key);

        encrypted.put("short", "fits");
        assertThatThrownBy(() -> encrypted.put("long", "x".repeat(300)))
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("parameter 'long'").hasMessageContaining("300 of value")
                .hasMessageContaining("at most 400 bytes");
        assertThatThrownBy(() -> encrypted.async().put("long", "x".repeat(300)).join())
                .isInstanceOf(CompletionException.class).hasCauseInstanceOf(InvalidArgumentException.class);

        assertThat(driver.values).containsOnlyKeys("short");
        assertThat(store.put("plain", "x".repeat(300)).value()).as("a plain store isn't checked").hasSize(300);
    }

    @Test
    void anEncryptedViewCantBeEncryptedAgain() {
        EncryptedParameterStore encrypted = store("app").encryptedWith(key);

        assertThatThrownBy(() -> encrypted.encryptedWith(key)).isInstanceOf(UnsupportedFeatureException.class)
                .hasMessageContaining("already");
        assertThatThrownBy(() -> encrypted.allowPlaintext().encryptedWith(keys, name -> "config"))
                .isInstanceOf(UnsupportedFeatureException.class);
        assertThatThrownBy(() -> store("app").encryptedWith((CryptoKey) null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void aWatchThroughTheViewReportsOpenedValues() throws Exception {
        EncryptedParameterStore encrypted = store("app", Duration.ofMinutes(5)).encryptedWith(key);
        encrypted.put("feature/a", "on");
        AtomicReference<ParameterChanges> seen = new AtomicReference<>();
        CountDownLatch changed = new CountDownLatch(1);

        try (AutoCloseable watch = encrypted.watch("feature", Duration.ofMillis(50), changes -> {
            seen.set(changes);
            changed.countDown();
        })) {
            store("app").encryptedWith(key).put("feature/a", "off");
            store("app").encryptedWith(key).put("feature/b", "new");
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
        }

        ParameterChanges changes = seen.get();
        Map<String, Parameter> reported = new java.util.HashMap<>(changes.added());
        reported.putAll(changes.updated());
        assertThat(reported.values()).allSatisfy(p -> assertThat(p.value()).doesNotStartWith("hlxs1:"));
        assertThat(reported.get("feature/a")).satisfiesAnyOf(
                p -> assertThat(p).isNull(), p -> assertThat(p.value()).isEqualTo("off"));
        assertThat(encrypted.require("feature/a")).as("the watch refreshed the view's cache").isEqualTo("off");
    }

    @Test
    void aWatchThatCantOpenAChangeReportsItAtTheNextPoll() throws Exception {
        ParameterStore plain = store("app");
        EncryptedParameterStore encrypted = plain.encryptedWith(key);
        encrypted.put("feature/a", "on");
        AtomicReference<ParameterChanges> seen = new AtomicReference<>();
        CountDownLatch changed = new CountDownLatch(1);

        try (AutoCloseable watch = encrypted.watch("feature", Duration.ofMillis(50), changes -> {
            seen.set(changes);
            changed.countDown();
        })) {
            plain.put("feature/a", "in the clear");           // can't be opened: the watch fails and retries
            Thread.sleep(300);
            assertThat(changed.getCount()).isEqualTo(1);
            encrypted.put("feature/a", "fixed");
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(seen.get().updated()).containsKey("feature/a");
        assertThat(seen.get().updated().get("feature/a").value()).isEqualTo("fixed");
        assertThat(encrypted.statistics().watchFailures()).isPositive();
    }

    /** The id sealed data names {@code key} by. */
    private static String keyId(CryptoKey key) {
        return key.encrypt(new byte[0], new byte[0]).keys().getFirst().keyId();
    }

    /** A map driver whose backend stores values up to a limit. */
    private static final class LimitedDriver implements ParameterDriver {
        final Map<String, Parameter> values = new ConcurrentHashMap<>();
        private final int limit;

        LimitedDriver(int limit) {
            this.limit = limit;
        }

        @Override
        public OptionalInt maxValueBytes() {
            return OptionalInt.of(limit);
        }

        @Override
        public Optional<Parameter> get(String name) {
            return Optional.ofNullable(values.get(name));
        }

        @Override
        public Optional<Parameter> get(String name, String version) {
            return get(name);
        }

        @Override
        public Map<String, Parameter> list(String prefix) {
            return Map.copyOf(values);
        }

        @Override
        public Parameter put(String name, String value) {
            Parameter p = new Parameter(name, value, "1", null);
            values.put(name, p);
            return p;
        }

        @Override
        public void delete(String name) {
            values.remove(name);
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.none();
        }

        @Override
        public CloudException translate(Throwable error) {
            return error instanceof CloudException c ? c : new UnknownCloudException(error.getMessage(), error, "memory", null);
        }

        @Override
        public <T> T unwrap(Class<T> nativeType) {
            throw new IllegalArgumentException();
        }
    }
}
