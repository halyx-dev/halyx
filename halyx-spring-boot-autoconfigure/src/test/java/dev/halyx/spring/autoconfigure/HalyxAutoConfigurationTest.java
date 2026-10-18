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
package dev.halyx.spring.autoconfigure;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import dev.halyx.Halyx;
import dev.halyx.cache.Cache;
import dev.halyx.cache.Codec;
import dev.halyx.parameters.ParameterKind;
import dev.halyx.parameters.ParameterStore;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.ThrottledException;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;
import dev.halyx.micrometer.ObservationInterceptor;
import dev.halyx.spring.autoconfigure.observation.HalyxObservationAutoConfiguration;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.Order;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
            secrets.async().put("db/user", "app").get(15, TimeUnit.SECONDS);
            assertThat(secrets.async().getAll("db").get(15, TimeUnit.SECONDS)).containsOnlyKeys("db/password", "db/user");
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

    // --- providers the Halyx bean registers --------------------------------------------------------

    @Test
    void resourcesUseProvidersACustomizerRegisters() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.blob-stores.archive.provider=archive", "halyx.blob-stores.archive.bucket=a",
                        "halyx.blob-stores.docs.provider=local")
                .withBean(HalyxBuilderCustomizer.class, () -> builder -> builder.register("archive", ProviderConfig.of("memory")))
                .run(context -> {
                    Halyx halyx = context.getBean(Halyx.class);
                    assertThat(context.getBean("archive", BlobStore.class)).isSameAs(halyx.provider("archive").blobStore("a"));
                    assertThat(context.getBean("docs", BlobStore.class)).isSameAs(halyx.provider("local").blobStore("docs"));
                });
    }

    @Test
    void theOnlyProviderACustomizerRegistersNeedsNoName() {
        runner.withPropertyValues("halyx.blob-stores.x.bucket=b")
                .withBean(HalyxBuilderCustomizer.class, () -> builder -> builder.register("only", ProviderConfig.of("memory")))
                .run(context -> assertThat(context.getBean("x", BlobStore.class).provider()).isEqualTo("memory"));
    }

    @Test
    void resourcesUseTheApplicationsOwnHalyxBean() {
        runner.withPropertyValues("halyx.blob-stores.x.provider=mine", "halyx.blob-stores.x.bucket=b")
                .withBean(Halyx.class, () -> Halyx.builder().register("mine", ProviderConfig.of("memory")).build())
                .run(context -> assertThat(context.getBean("x", BlobStore.class))
                        .isSameAs(context.getBean(Halyx.class).provider("mine").blobStore("b")));
    }

    @Test
    void anUnknownProviderListsTheRegisteredOnes() {
        runner.withPropertyValues("halyx.providers.local.type=memory", "halyx.blob-stores.x.provider=missing")
                .withBean(HalyxBuilderCustomizer.class, () -> builder -> builder.register("extra", ProviderConfig.of("memory")))
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("halyx.blob-stores.x.provider refers to unknown provider 'missing'")
                        .hasStackTraceContaining("Registered providers: [local, extra]"));
    }

    // --- missing modules ---------------------------------------------------------------------------

    @Test
    void r2dbcDatabasesWithoutHalyxR2dbcFailAtStartup() {
        runner.withClassLoader(new FilteredClassLoader("dev.halyx.r2dbc"))
                .withPropertyValues("halyx.providers.local.type=memory", "halyx.r2dbc-databases.orders.engine=postgresql",
                        "halyx.r2dbc-databases.orders.host=db", "halyx.r2dbc-databases.orders.database=orders",
                        "halyx.r2dbc-databases.orders.auth.username=app", "halyx.r2dbc-databases.orders.auth.password=p")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(
                        "halyx.r2dbc-databases is configured, but halyx-r2dbc is not on the classpath"));
    }

    @Test
    void aSpringKafkaClusterWithoutSpringKafkaFailsAtStartup() {
        runner.withClassLoader(new FilteredClassLoader("org.springframework.kafka"))
                .withPropertyValues("halyx.providers.local.type=memory", "halyx.spring-kafka.cluster=events")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(
                        "halyx.spring-kafka.cluster is set, but Spring for Apache Kafka is not on the classpath"));
    }

    // --- cache codecs --------------------------------------------------------------------------------

    public record Purchase(String id, int items) implements Serializable {
    }

    @Configuration(proxyBeanMethods = false)
    static class PurchaseCodec {
        @Bean
        Codec<Purchase> orderCodec() {
            return Codec.of(order -> (order.id() + ":" + order.items()).getBytes(StandardCharsets.UTF_8), bytes -> {
                String[] parts = new String(bytes, StandardCharsets.UTF_8).split(":");
                return new Purchase(parts[0], Integer.parseInt(parts[1]));
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void cachesStoreWhatTheirCodecSays() {
        String endpoint = "=redis://localhost:6379";
        runner.withUserConfiguration(PurchaseCodec.class)
                .withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.caches.text.endpoint" + endpoint,
                        "halyx.caches.raw.endpoint" + endpoint, "halyx.caches.raw.codec=bytes",
                        "halyx.caches.serialized.endpoint" + endpoint, "halyx.caches.serialized.codec=jdk",
                        "halyx.caches.serialized.trusted-packages=dev.halyx.spring",
                        "halyx.caches.json.endpoint" + endpoint, "halyx.caches.json.codec=json",
                        "halyx.caches.json.trusted-packages=dev.halyx.spring",
                        "halyx.caches.orders.endpoint" + endpoint, "halyx.caches.orders.codec=orderCodec")
                .run(context -> {
                    Purchase order = new Purchase("o-1", 3);
                    context.getBean("text", Cache.class).put("k", "v");
                    assertThat(context.getBean("text", Cache.class).get("k")).hasValue("v");
                    Cache<byte[]> raw = context.getBean("raw", Cache.class);
                    raw.put("k", new byte[] {1, 2});
                    assertThat(raw.get("k")).hasValueSatisfying(v -> assertThat(v).containsExactly(1, 2));
                    for (String name : List.of("serialized", "json", "orders")) {
                        Cache<Object> cache = context.getBean(name, Cache.class);
                        cache.put("k", order);
                        assertThat(cache.get("k")).as(name).hasValue(order);
                    }
                    // A Codec bean's value type is the cache bean's, so Cache<Purchase> injects by type alone.
                    Cache<Purchase> byType = (Cache<Purchase>) context.getBeanProvider(
                            ResolvableType.forClassWithGenerics(Cache.class, Purchase.class)).getObject();
                    assertThat(byType).isSameAs(context.getBean("orders"));
                    assertThat(context.getBeanProvider(ResolvableType.forClassWithGenerics(Cache.class, String.class))
                            .getObject()).isSameAs(context.getBean("text"));
                });
    }

    @Test
    void jsonCachesNeedTrustedPackages() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.caches.objects.endpoint=redis://localhost:6379", "halyx.caches.objects.codec=json")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(
                        "halyx.caches.objects.trusted-packages is required with codec=json"));
    }

    @Test
    void cachePasswordErrorsNameTheirProperty() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.caches.sessions.endpoint=redis://localhost:6379", "halyx.caches.sessions.auth.type=password")
                .run(context -> assertThat(context).hasFailed().getFailure().hasStackTraceContaining(
                        "halyx.caches.sessions.auth.password is required for password auth"));
    }
}
