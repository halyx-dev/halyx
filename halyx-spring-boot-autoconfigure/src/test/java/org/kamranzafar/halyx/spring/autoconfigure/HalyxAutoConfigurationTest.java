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
package org.kamranzafar.halyx.spring.autoconfigure;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.cache.Cache;
import org.kamranzafar.halyx.parameters.ParameterKind;
import org.kamranzafar.halyx.parameters.ParameterStore;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.error.ThrottledException;
import org.kamranzafar.halyx.interceptor.Interceptor;
import org.kamranzafar.halyx.interceptor.Invocation;
import org.kamranzafar.halyx.interceptor.OperationContext;
import org.kamranzafar.halyx.spring.autoconfigure.observation.ObservationInterceptor;
import org.kamranzafar.halyx.spring.autoconfigure.observation.HalyxObservationAutoConfiguration;
import org.kamranzafar.halyx.storage.BlobContent;
import org.kamranzafar.halyx.storage.BlobKey;
import org.kamranzafar.halyx.storage.BlobStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HalyxAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class,
                    HalyxObservationAutoConfiguration.class));

    private static final String[] MEMORY = {
            "halyx.providers.local.type=memory",
            "halyx.blob-stores.documents.bucket=docs"
    };

    @Test
    void createsHalyxWithoutAnyConfiguration() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Halyx.class);
            assertThat(context.getBean(Halyx.class).names()).isEmpty();
            assertThat(context).doesNotHaveBean(BlobStore.class);
        });
    }

    @Test
    void registersNamedBlobStoreBeans() {
        runner.withPropertyValues(MEMORY).run(context -> {
            BlobStore store = context.getBean("documents", BlobStore.class);
            store.put(BlobKey.of("k"), BlobContent.ofString("v"));

            assertThat(store.provider()).isEqualTo("memory");
            assertThat(store.bucket()).isEqualTo("docs");
            assertThat(store.getBytes(BlobKey.of("k"))).asString().isEqualTo("v");
            assertThat(context.getBean(Halyx.class).provider("local").blobStore("docs")).isSameAs(store);
        });
    }

    @Test
    void bucketDefaultsToBeanNameAndProviderToTheOnlyOne() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.blob-stores.uploads.bucket=")
                .run(context -> assertThat(context.getBean("uploads", BlobStore.class).bucket()).isEqualTo("uploads"));
    }

    @Test
    void registersNamedCacheBeans() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                "halyx.caches.sessions.endpoint=redis://localhost:6379",
                "halyx.caches.sessions.default-ttl=30m",
                "halyx.caches.tokens.endpoint=redis://localhost:6379",
                "halyx.caches.tokens.namespace=").run(context -> {
            @SuppressWarnings("unchecked")
            Cache<String> sessions = context.getBean("sessions", Cache.class);
            @SuppressWarnings("unchecked")
            Cache<String> tokens = context.getBean("tokens", Cache.class);
            sessions.put("user-1", "alice");

            assertThat(sessions.namespace()).isEqualTo("sessions");
            assertThat(sessions.get("user-1")).hasValue("alice");
            assertThat(sessions.ttl("user-1")).hasValueSatisfying(ttl -> assertThat(ttl).isPositive());
            assertThat(tokens.namespace()).isEmpty();
            assertThat(tokens.get("user-1")).as("different namespace").isEmpty();
        });
    }

    @Test
    void cacheAuthIsMapped() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                "halyx.caches.c.endpoint=redis://localhost:6379",
                "halyx.caches.c.auth.type=password",
                "halyx.caches.c.auth.username=app",
                "halyx.caches.c.auth.password=pw").run(context ->
                assertThat(context).hasBean("c"));
    }

    @Test
    void cacheWithoutEndpointFailsAtStartup() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.caches.c.namespace=x")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("halyx.caches.c.endpoint is required"));
    }

    @Test
    void passwordAuthWithoutPasswordFailsAtStartup() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.caches.c.endpoint=redis://localhost:6379", "halyx.caches.c.auth.type=password")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("halyx.caches.c.auth.password is required"));
    }

    @Test
    void registersParameterStoreBeans() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                "halyx.parameter-stores.app-secrets.prefix=orders",
                "halyx.parameter-stores.app-config.kind=parameters").run(context -> {
            ParameterStore secrets = context.getBean("app-secrets", ParameterStore.class);
            secrets.put("db/password", "pw");

            assertThat(secrets.kind()).isEqualTo(ParameterKind.SECRETS);
            assertThat(secrets.require("db/password")).isEqualTo("pw");
            assertThat(context.getBean("app-config", ParameterStore.class).kind()).isEqualTo(ParameterKind.PARAMETERS);
        });
    }

    @Test
    void severalProvidersAndStores() {
        runner.withPropertyValues(
                "halyx.providers.primary.type=memory",
                "halyx.providers.archive.type=memory",
                "halyx.blob-stores.orders.provider=primary",
                "halyx.blob-stores.history.provider=archive",
                "halyx.blob-stores.history.bucket=orders").run(context -> {
            assertThat(context.getBeansOfType(BlobStore.class)).containsOnlyKeys("orders", "history");
            context.getBean("orders", BlobStore.class).put(BlobKey.of("k"), BlobContent.ofString("v"));
            assertThat(context.getBean("history", BlobStore.class).exists(BlobKey.of("k"))).isFalse();
        });
    }

    @Test
    void providerSpecificPropertiesKeepDottedKeys() {
        runner.withPropertyValues(
                "halyx.providers.s3.type=memory",
                "halyx.providers.s3.region=eu-west-2",
                "halyx.providers.s3.endpoint=http://localhost:4566",
                "halyx.providers.s3.credentials.type=static",
                "halyx.providers.s3.credentials.id=test",
                "halyx.providers.s3.credentials.secret=s3cr3t-value",
                "halyx.providers.s3.properties.aws.s3.path-style=true").run(context -> {
            ProviderConfig config = context.getBean(Halyx.class).provider("s3").config();
            assertThat(config.region()).hasValue("eu-west-2");
            assertThat(config.endpoint()).hasValueSatisfying(uri -> assertThat(uri).hasPort(4566));
            assertThat(config.property("aws.s3.path-style")).hasValue("true");
            assertThat(config.credentials().toString()).contains("test").doesNotContain("s3cr3t-value");
        });
    }

    @Test
    void unknownProviderReferenceFailsAtStartup() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.blob-stores.x.provider=missing")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("halyx.blob-stores.x.provider").hasMessageContaining("missing"));
    }

    @Test
    void ambiguousProviderFailsAtStartup() {
        runner.withPropertyValues("halyx.providers.a.type=memory", "halyx.providers.b.type=memory",
                        "halyx.blob-stores.x.bucket=b")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("halyx.blob-stores.x.provider is required"));
    }

    @Test
    void missingProviderModuleFailsWithHint() {
        runner.withPropertyValues("halyx.providers.x.type=nimbus")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("Add the halyx-nimbus dependency"));
    }

    @Test
    void incompleteStaticCredentialsFailAtStartup() {
        runner.withPropertyValues("halyx.providers.x.type=memory", "halyx.providers.x.credentials.type=static")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("need both id and secret"));
    }

    @Test
    void userDefinedHalyxWins() {
        runner.withPropertyValues(MEMORY)
                .withBean(Halyx.class, () -> Halyx.builder().register("local", ProviderConfig.of("memory")).build())
                .run(context -> {
                    assertThat(context).hasSingleBean(Halyx.class);
                    assertThat(context.getBean("documents", BlobStore.class).provider()).isEqualTo("memory");
                });
    }

    @Test
    void interceptorBeansRunInOrder() {
        List<String> calls = new ArrayList<>();
        runner.withPropertyValues(MEMORY)
                .withBean("second", Interceptor.class, () -> new Recording("second", calls))
                .withBean("first", FirstRecording.class, () -> new FirstRecording(calls))
                .run(context -> {
                    context.getBean("documents", BlobStore.class).exists(BlobKey.of("k"));
                    assertThat(calls).containsExactly("first:head", "second:head");
                });
    }

    @Test
    void retriesWhenEnabled() {
        TestProviders.Flaky flaky = new TestProviders.Flaky(2);
        runner.withPropertyValues("halyx.providers.flaky.type=flaky", "halyx.blob-stores.store.bucket=b",
                        "halyx.retry.enabled=true", "halyx.retry.initial-backoff=1ms")
                .withBean(HalyxBuilderCustomizer.class, () -> builder -> builder.plugin(flaky))
                .run(context -> {
                    assertThat(context.getBean("store", BlobStore.class).exists(BlobKey.of("k"))).isFalse();
                    assertThat(flaky.calls).hasValue(3);
                });
    }

    @Test
    void doesNotRetryByDefault() {
        TestProviders.Flaky flaky = new TestProviders.Flaky(1);
        runner.withPropertyValues("halyx.providers.flaky.type=flaky", "halyx.blob-stores.store.bucket=b")
                .withBean(HalyxBuilderCustomizer.class, () -> builder -> builder.plugin(flaky))
                .run(context -> {
                    BlobStore store = context.getBean("store", BlobStore.class);
                    assertThatThrownBy(() -> store.exists(BlobKey.of("k")))
                            .isInstanceOf(ThrottledException.class);
                    assertThat(flaky.calls).hasValue(1);
                });
    }

    @Test
    void observesOperationsWhenAnObservationRegistryExists() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        runner.withPropertyValues(MEMORY)
                .withBean(io.micrometer.observation.ObservationRegistry.class, () -> registry)
                .run(context -> {
                    context.getBean("documents", BlobStore.class).put(BlobKey.of("k"), BlobContent.ofString("v"));
                    TestObservationRegistryAssert.assertThat(registry)
                            .hasObservationWithNameEqualTo(ObservationInterceptor.OBSERVATION_NAME)
                            .that()
                            .hasLowCardinalityKeyValue("halyx.operation", "put")
                            .hasLowCardinalityKeyValue("halyx.provider", "memory")
                            .hasLowCardinalityKeyValue("halyx.service", "storage")
                            .hasHighCardinalityKeyValue("halyx.resource", "docs/k")
                            .hasBeenStopped();
                });
    }

    @Test
    void observationsCanBeDisabled() {
        runner.withPropertyValues(MEMORY)
                .withPropertyValues("halyx.observations.enabled=false")
                .withBean(io.micrometer.observation.ObservationRegistry.class, TestObservationRegistry::create)
                .run(context -> assertThat(context).doesNotHaveBean(ObservationInterceptor.class));
    }

    // --- test doubles --------------------------------------------------------------------------

    static class Recording implements Interceptor {
        private final String name;
        private final List<String> calls;

        Recording(String name, List<String> calls) {
            this.name = name;
            this.calls = calls;
        }

        @Override
        public <T> T intercept(OperationContext context, Invocation<T> next) {
            calls.add(name + ":" + context.operation());
            return next.proceed();
        }
    }

    @Order(1)
    static class FirstRecording extends Recording {
        FirstRecording(List<String> calls) {
            super("first", calls);
        }
    }
}
