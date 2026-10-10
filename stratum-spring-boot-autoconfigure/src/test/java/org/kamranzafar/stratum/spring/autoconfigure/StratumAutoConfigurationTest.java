package org.kamranzafar.stratum.spring.autoconfigure;

import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.Stratum;
import org.kamranzafar.stratum.cache.Cache;
import org.kamranzafar.stratum.config.ProviderConfig;
import org.kamranzafar.stratum.error.ThrottledException;
import org.kamranzafar.stratum.interceptor.Interceptor;
import org.kamranzafar.stratum.interceptor.Invocation;
import org.kamranzafar.stratum.interceptor.OperationContext;
import org.kamranzafar.stratum.spring.autoconfigure.observation.ObservationInterceptor;
import org.kamranzafar.stratum.spring.autoconfigure.observation.StratumObservationAutoConfiguration;
import org.kamranzafar.stratum.storage.BlobContent;
import org.kamranzafar.stratum.storage.BlobKey;
import org.kamranzafar.stratum.storage.BlobStore;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StratumAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StratumAutoConfiguration.class,
                    StratumObservationAutoConfiguration.class));

    private static final String[] MEMORY = {
            "stratum.providers.local.type=memory",
            "stratum.blob-stores.documents.bucket=docs"
    };

    @Test
    void createsStratumWithoutAnyConfiguration() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(Stratum.class);
            assertThat(context.getBean(Stratum.class).names()).isEmpty();
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
            assertThat(context.getBean(Stratum.class).provider("local").blobStore("docs")).isSameAs(store);
        });
    }

    @Test
    void bucketDefaultsToBeanNameAndProviderToTheOnlyOne() {
        runner.withPropertyValues("stratum.providers.local.type=memory", "stratum.blob-stores.uploads.bucket=")
                .run(context -> assertThat(context.getBean("uploads", BlobStore.class).bucket()).isEqualTo("uploads"));
    }

    @Test
    void registersNamedCacheBeans() {
        runner.withPropertyValues("stratum.providers.local.type=memory",
                "stratum.caches.sessions.endpoint=redis://localhost:6379",
                "stratum.caches.sessions.default-ttl=30m",
                "stratum.caches.tokens.endpoint=redis://localhost:6379",
                "stratum.caches.tokens.namespace=").run(context -> {
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
        runner.withPropertyValues("stratum.providers.local.type=memory",
                "stratum.caches.c.endpoint=redis://localhost:6379",
                "stratum.caches.c.auth.type=password",
                "stratum.caches.c.auth.username=app",
                "stratum.caches.c.auth.password=pw").run(context ->
                assertThat(context).hasBean("c"));
    }

    @Test
    void cacheWithoutEndpointFailsAtStartup() {
        runner.withPropertyValues("stratum.providers.local.type=memory", "stratum.caches.c.namespace=x")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("stratum.caches.c.endpoint is required"));
    }

    @Test
    void passwordAuthWithoutPasswordFailsAtStartup() {
        runner.withPropertyValues("stratum.providers.local.type=memory",
                        "stratum.caches.c.endpoint=redis://localhost:6379", "stratum.caches.c.auth.type=password")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("stratum.caches.c.auth.password is required"));
    }

    @Test
    void severalProvidersAndStores() {
        runner.withPropertyValues(
                "stratum.providers.primary.type=memory",
                "stratum.providers.archive.type=memory",
                "stratum.blob-stores.orders.provider=primary",
                "stratum.blob-stores.history.provider=archive",
                "stratum.blob-stores.history.bucket=orders").run(context -> {
            assertThat(context.getBeansOfType(BlobStore.class)).containsOnlyKeys("orders", "history");
            context.getBean("orders", BlobStore.class).put(BlobKey.of("k"), BlobContent.ofString("v"));
            assertThat(context.getBean("history", BlobStore.class).exists(BlobKey.of("k"))).isFalse();
        });
    }

    @Test
    void providerSpecificPropertiesKeepDottedKeys() {
        runner.withPropertyValues(
                "stratum.providers.s3.type=memory",
                "stratum.providers.s3.region=eu-west-2",
                "stratum.providers.s3.endpoint=http://localhost:4566",
                "stratum.providers.s3.credentials.type=static",
                "stratum.providers.s3.credentials.id=test",
                "stratum.providers.s3.credentials.secret=s3cr3t-value",
                "stratum.providers.s3.properties.aws.s3.path-style=true").run(context -> {
            ProviderConfig config = context.getBean(Stratum.class).provider("s3").config();
            assertThat(config.region()).hasValue("eu-west-2");
            assertThat(config.endpoint()).hasValueSatisfying(uri -> assertThat(uri).hasPort(4566));
            assertThat(config.property("aws.s3.path-style")).hasValue("true");
            assertThat(config.credentials().toString()).contains("test").doesNotContain("s3cr3t-value");
        });
    }

    @Test
    void unknownProviderReferenceFailsAtStartup() {
        runner.withPropertyValues("stratum.providers.local.type=memory", "stratum.blob-stores.x.provider=missing")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("stratum.blob-stores.x.provider").hasMessageContaining("missing"));
    }

    @Test
    void ambiguousProviderFailsAtStartup() {
        runner.withPropertyValues("stratum.providers.a.type=memory", "stratum.providers.b.type=memory",
                        "stratum.blob-stores.x.bucket=b")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("stratum.blob-stores.x.provider is required"));
    }

    @Test
    void missingProviderModuleFailsWithHint() {
        runner.withPropertyValues("stratum.providers.x.type=nimbus")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("Add the stratum-nimbus dependency"));
    }

    @Test
    void incompleteStaticCredentialsFailAtStartup() {
        runner.withPropertyValues("stratum.providers.x.type=memory", "stratum.providers.x.credentials.type=static")
                .run(context -> assertThat(context).hasFailed().getFailure()
                        .hasMessageContaining("need both id and secret"));
    }

    @Test
    void userDefinedStratumWins() {
        runner.withPropertyValues(MEMORY)
                .withBean(Stratum.class, () -> Stratum.builder().register("local", ProviderConfig.of("memory")).build())
                .run(context -> {
                    assertThat(context).hasSingleBean(Stratum.class);
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
        runner.withPropertyValues("stratum.providers.flaky.type=flaky", "stratum.blob-stores.store.bucket=b",
                        "stratum.retry.enabled=true", "stratum.retry.initial-backoff=1ms")
                .withBean(StratumBuilderCustomizer.class, () -> builder -> builder.plugin(flaky))
                .run(context -> {
                    assertThat(context.getBean("store", BlobStore.class).exists(BlobKey.of("k"))).isFalse();
                    assertThat(flaky.calls).hasValue(3);
                });
    }

    @Test
    void doesNotRetryByDefault() {
        TestProviders.Flaky flaky = new TestProviders.Flaky(1);
        runner.withPropertyValues("stratum.providers.flaky.type=flaky", "stratum.blob-stores.store.bucket=b")
                .withBean(StratumBuilderCustomizer.class, () -> builder -> builder.plugin(flaky))
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
                            .hasLowCardinalityKeyValue("stratum.operation", "put")
                            .hasLowCardinalityKeyValue("stratum.provider", "memory")
                            .hasLowCardinalityKeyValue("stratum.service", "storage")
                            .hasHighCardinalityKeyValue("stratum.resource", "docs/k")
                            .hasBeenStopped();
                });
    }

    @Test
    void observationsCanBeDisabled() {
        runner.withPropertyValues(MEMORY)
                .withPropertyValues("stratum.observations.enabled=false")
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
