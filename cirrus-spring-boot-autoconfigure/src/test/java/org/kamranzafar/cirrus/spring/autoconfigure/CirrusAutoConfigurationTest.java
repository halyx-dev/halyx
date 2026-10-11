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
package org.kamranzafar.cirrus.spring.autoconfigure;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.cache.Cache;
import org.kamranzafar.cirrus.parameters.ParameterKind;
import org.kamranzafar.cirrus.parameters.ParameterStore;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.error.ThrottledException;
import org.kamranzafar.cirrus.interceptor.Interceptor;
import org.kamranzafar.cirrus.interceptor.Invocation;
import org.kamranzafar.cirrus.interceptor.OperationContext;
import org.kamranzafar.cirrus.spring.autoconfigure.observation.ObservationInterceptor;
import org.kamranzafar.cirrus.spring.autoconfigure.observation.CirrusObservationAutoConfiguration;
import org.kamranzafar.cirrus.storage.BlobContent;
import org.kamranzafar.cirrus.storage.BlobKey;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CirrusAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CirrusAutoConfiguration.class,
                    CirrusObservationAutoConfiguration.class));

    private static final String[] MEMORY = {
            "cirrus.providers.local.type=memory",
            "cirrus.blob-stores.documents.bucket=docs"
    };

    @Test
    void createsCirrusWithoutAnyConfiguration() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Cirrus.class);
            assertThat(context.getBean(Cirrus.class).names()).isEmpty();
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
            assertThat(context.getBean(Cirrus.class).provider("local").blobStore("docs")).isSameAs(store);
        });
    }

    @Test
    void bucketDefaultsToBeanNameAndProviderToTheOnlyOne() {
        runner.withPropertyValues("cirrus.providers.local.type=memory", "cirrus.blob-stores.uploads.bucket=")
                .run(context -> assertThat(context.getBean("uploads", BlobStore.class).bucket()).isEqualTo("uploads"));
    }

    @Test
    void registersNamedCacheBeans() {
        runner.withPropertyValues("cirrus.providers.local.type=memory",
                "cirrus.caches.sessions.endpoint=redis://localhost:6379",
                "cirrus.caches.sessions.default-ttl=30m",
                "cirrus.caches.tokens.endpoint=redis://localhost:6379",
                "cirrus.caches.tokens.namespace=").run(context -> {
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
        runner.withPropertyValues("cirrus.providers.local.type=memory",
                "cirrus.caches.c.endpoint=redis://localhost:6379",
                "cirrus.caches.c.auth.type=password",
                "cirrus.caches.c.auth.username=app",
                "cirrus.caches.c.auth.password=pw").run(context ->
                assertThat(context).hasBean("c"));
    }

    @Test
    void cacheWithoutEndpointFailsAtStartup() {
        runner.withPropertyValues("cirrus.providers.local.type=memory", "cirrus.caches.c.namespace=x")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("cirrus.caches.c.endpoint is required"));
    }

    @Test
    void passwordAuthWithoutPasswordFailsAtStartup() {
        runner.withPropertyValues("cirrus.providers.local.type=memory",
                        "cirrus.caches.c.endpoint=redis://localhost:6379", "cirrus.caches.c.auth.type=password")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("cirrus.caches.c.auth.password is required"));
    }

    @Test
    void registersParameterStoreBeans() {
        runner.withPropertyValues("cirrus.providers.local.type=memory",
                "cirrus.parameter-stores.app-secrets.prefix=orders",
                "cirrus.parameter-stores.app-config.kind=parameters").run(context -> {
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
                "cirrus.providers.primary.type=memory",
                "cirrus.providers.archive.type=memory",
                "cirrus.blob-stores.orders.provider=primary",
                "cirrus.blob-stores.history.provider=archive",
                "cirrus.blob-stores.history.bucket=orders").run(context -> {
            assertThat(context.getBeansOfType(BlobStore.class)).containsOnlyKeys("orders", "history");
            context.getBean("orders", BlobStore.class).put(BlobKey.of("k"), BlobContent.ofString("v"));
            assertThat(context.getBean("history", BlobStore.class).exists(BlobKey.of("k"))).isFalse();
        });
    }

    @Test
    void providerSpecificPropertiesKeepDottedKeys() {
        runner.withPropertyValues(
                "cirrus.providers.s3.type=memory",
                "cirrus.providers.s3.region=eu-west-2",
                "cirrus.providers.s3.endpoint=http://localhost:4566",
                "cirrus.providers.s3.credentials.type=static",
                "cirrus.providers.s3.credentials.id=test",
                "cirrus.providers.s3.credentials.secret=s3cr3t-value",
                "cirrus.providers.s3.properties.aws.s3.path-style=true").run(context -> {
            ProviderConfig config = context.getBean(Cirrus.class).provider("s3").config();
            assertThat(config.region()).hasValue("eu-west-2");
            assertThat(config.endpoint()).hasValueSatisfying(uri -> assertThat(uri).hasPort(4566));
            assertThat(config.property("aws.s3.path-style")).hasValue("true");
            assertThat(config.credentials().toString()).contains("test").doesNotContain("s3cr3t-value");
        });
    }

    @Test
    void unknownProviderReferenceFailsAtStartup() {
        runner.withPropertyValues("cirrus.providers.local.type=memory", "cirrus.blob-stores.x.provider=missing")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("cirrus.blob-stores.x.provider").hasMessageContaining("missing"));
    }

    @Test
    void ambiguousProviderFailsAtStartup() {
        runner.withPropertyValues("cirrus.providers.a.type=memory", "cirrus.providers.b.type=memory",
                        "cirrus.blob-stores.x.bucket=b")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("cirrus.blob-stores.x.provider is required"));
    }

    @Test
    void missingProviderModuleFailsWithHint() {
        runner.withPropertyValues("cirrus.providers.x.type=nimbus")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("Add the cirrus-nimbus dependency"));
    }

    @Test
    void incompleteStaticCredentialsFailAtStartup() {
        runner.withPropertyValues("cirrus.providers.x.type=memory", "cirrus.providers.x.credentials.type=static")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("need both id and secret"));
    }

    @Test
    void userDefinedCirrusWins() {
        runner.withPropertyValues(MEMORY)
                .withBean(Cirrus.class, () -> Cirrus.builder().register("local", ProviderConfig.of("memory")).build())
                .run(context -> {
                    assertThat(context).hasSingleBean(Cirrus.class);
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
        runner.withPropertyValues("cirrus.providers.flaky.type=flaky", "cirrus.blob-stores.store.bucket=b",
                        "cirrus.retry.enabled=true", "cirrus.retry.initial-backoff=1ms")
                .withBean(CirrusBuilderCustomizer.class, () -> builder -> builder.plugin(flaky))
                .run(context -> {
                    assertThat(context.getBean("store", BlobStore.class).exists(BlobKey.of("k"))).isFalse();
                    assertThat(flaky.calls).hasValue(3);
                });
    }

    @Test
    void doesNotRetryByDefault() {
        TestProviders.Flaky flaky = new TestProviders.Flaky(1);
        runner.withPropertyValues("cirrus.providers.flaky.type=flaky", "cirrus.blob-stores.store.bucket=b")
                .withBean(CirrusBuilderCustomizer.class, () -> builder -> builder.plugin(flaky))
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
                            .hasLowCardinalityKeyValue("cirrus.operation", "put")
                            .hasLowCardinalityKeyValue("cirrus.provider", "memory")
                            .hasLowCardinalityKeyValue("cirrus.service", "storage")
                            .hasHighCardinalityKeyValue("cirrus.resource", "docs/k")
                            .hasBeenStopped();
                });
    }

    @Test
    void observationsCanBeDisabled() {
        runner.withPropertyValues(MEMORY)
                .withPropertyValues("cirrus.observations.enabled=false")
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
