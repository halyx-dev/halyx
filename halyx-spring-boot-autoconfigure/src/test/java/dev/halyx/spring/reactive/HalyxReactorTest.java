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
package dev.halyx.spring.reactive;

import dev.halyx.Halyx;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.Subscription;
import dev.halyx.reactor.ReactiveBlobStore;
import dev.halyx.reactor.ReactiveCache;
import dev.halyx.reactor.ReactiveDocumentStore;
import dev.halyx.reactor.ReactiveEventPublisher;
import dev.halyx.reactor.ReactiveHalyx;
import dev.halyx.reactor.ReactiveParameterStore;
import dev.halyx.reactor.ReactivePublisher;
import dev.halyx.reactor.ReactiveStreamConsumer;
import dev.halyx.reactor.ReactiveStreamProducer;
import dev.halyx.reactor.ReactiveSubscription;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.observation.HalyxObservationAutoConfiguration;
import dev.halyx.spring.autoconfigure.reactor.HalyxReactorAutoConfiguration;
import dev.halyx.spring.messaging.HalyxListener;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import dev.halyx.storage.BlobStore;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** halyx-reactor in Spring Boot, on Boot 3.5 and 4: the reactive beans, the scheduler, listeners and tracing. */
class HalyxReactorTest {

    private static final String[] SERVICES = {
            "halyx.providers.local.type=memory",
            "halyx.blob-stores.invoices.bucket=acme-invoices",
            "halyx.blob-stores.avatars.bucket=acme-avatars",
            "halyx.document-stores.orders.sort-key=sk",
            "halyx.topics.order-events.name=orders",
            "halyx.subscriptions.billing.name=orders-billing",
            "halyx.subscriptions.billing.topic=orders",
            "halyx.event-buses.bus.name=events",
            "halyx.caches.sessions.endpoint=redis://localhost:6379",
            "halyx.parameter-stores.config.kind=parameters",
            "halyx.stream-clusters.events.bootstrap-servers=memory:9092",
            "halyx.stream-producers.ledger-out.topic=ledger",
            "halyx.stream-consumers.ledger.group=billing",
            "halyx.stream-consumers.ledger.start-position=earliest",
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxReactorAutoConfiguration.class));

    static class InvoiceService {
        final ReactiveBlobStore invoices;
        final ReactiveBlobStore avatars;

        InvoiceService(@Qualifier("invoices") ReactiveBlobStore invoices, @Qualifier("avatars") ReactiveBlobStore avatars) {
            this.invoices = invoices;
            this.avatars = avatars;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Services {
        @Bean
        InvoiceService invoiceService(@Qualifier("invoices") ReactiveBlobStore invoices,
                                      @Qualifier("avatars") ReactiveBlobStore avatars) {
            return new InvoiceService(invoices, avatars);
        }
    }

    @Test
    void everyServiceBeanHasAReactiveViewQualifiedByItsName() {
        runner.withPropertyValues(SERVICES).withUserConfiguration(Services.class).run(context -> {
            assertThat(context).hasNotFailed();
            InvoiceService service = context.getBean(InvoiceService.class);
            assertThat(service.invoices.blocking()).isSameAs(context.getBean("invoices", BlobStore.class));
            assertThat(service.avatars.bucket()).isEqualTo("acme-avatars");

            assertThat(context.getBean("ordersReactive")).isInstanceOf(ReactiveDocumentStore.class);
            assertThat(context.getBean("order-eventsReactive", ReactivePublisher.class).blocking())
                    .isSameAs(context.getBean("order-events", Publisher.class));
            assertThat(context.getBean("billingReactive", ReactiveSubscription.class).blocking())
                    .isSameAs(context.getBean("billing", Subscription.class));
            assertThat(context.getBean("busReactive")).isInstanceOf(ReactiveEventPublisher.class);
            assertThat(context.getBean("sessionsReactive")).isInstanceOf(ReactiveCache.class);
            assertThat(context.getBean("configReactive")).isInstanceOf(ReactiveParameterStore.class);
            assertThat(context.getBean("ledger-outReactive")).isInstanceOf(ReactiveStreamProducer.class);
            assertThat(context.getBean("ledgerReactive")).isInstanceOf(ReactiveStreamConsumer.class);
            assertThat(context.getBeansOfType(ReactiveBlobStore.class)).containsOnlyKeys("invoicesReactive", "avatarsReactive");

            ReactiveBlobStore invoices = service.invoices;
            invoices.put(BlobKey.of("k"), BlobContent.ofString("v")).block();
            assertThat(invoices.getBytes(BlobKey.of("k")).block()).asString().isEqualTo("v");
        });
    }

    @Test
    void callsRunOnVirtualThreadsByDefaultAndTheSchedulerIsDisposedWithTheContext() {
        Scheduler[] made = new Scheduler[1];
        runner.withPropertyValues(SERVICES).run(context -> {
            ReactiveHalyx rx = context.getBean(ReactiveHalyx.class);
            made[0] = rx.scheduler();
            Boolean virtual = context.getBean("invoicesReactive", ReactiveBlobStore.class).exists(BlobKey.of("k"))
                    .map(found -> Thread.currentThread().isVirtual()).block();
            assertThat(virtual).isTrue();
        });
        assertThat(made[0].isDisposed()).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    static class AppScheduler {
        @Bean(destroyMethod = "dispose")
        Scheduler halyxCalls() {
            return ReactiveHalyx.virtualThreadScheduler("app-halyx");
        }
    }

    @Test
    void theSchedulerCanBeTheApplicationsBeanOrBoundedElastic() {
        runner.withPropertyValues(SERVICES).withPropertyValues("halyx.reactor.scheduler=halyxCalls",
                "halyx.reactor.max-concurrency=4").withUserConfiguration(AppScheduler.class).run(context -> {
            String thread = context.getBean("invoicesReactive", ReactiveBlobStore.class).exists(BlobKey.of("k"))
                    .map(found -> Thread.currentThread().getName()).block();
            assertThat(thread).startsWith("app-halyx-");
            assertThat(context.getBean(ReactiveHalyx.class).scheduler()).isSameAs(context.getBean("halyxCalls"));
        });
        runner.withPropertyValues(SERVICES).withPropertyValues("halyx.reactor.scheduler=bounded-elastic").run(context ->
                assertThat(context.getBean(ReactiveHalyx.class).scheduler()).isSameAs(Schedulers.boundedElastic()));
        runner.withPropertyValues(SERVICES).withPropertyValues("halyx.reactor.scheduler=nope").run(context ->
                assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("halyx.reactor.scheduler")
                        .hasMessageContaining("nope"));
    }

    @Test
    void theReactiveBeansCanBeTurnedOff() {
        runner.withPropertyValues(SERVICES).withPropertyValues("halyx.reactor.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ReactiveHalyx.class);
            assertThat(context.getBeansOfType(ReactiveBlobStore.class)).isEmpty();
            assertThat(context).hasBean("invoices");
        });
    }

    static class AsyncListeners {
        final Set<String> handled = ConcurrentHashMap.newKeySet();
        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(3);

        @HalyxListener("billing")
        Mono<Void> onOrder(String body) {
            return Mono.fromRunnable(() -> {
                if (body.equals("fails-once") && attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("first attempt fails");
                }
                handled.add(body);
                done.countDown();
            });
        }

        @HalyxListener("ledger")
        CompletableFuture<Void> onEntry(String value) {
            return CompletableFuture.runAsync(() -> {
                handled.add("ledger:" + value);
                done.countDown();
            });
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Listeners {
        @Bean
        AsyncListeners asyncListeners() {
            return new AsyncListeners();
        }
    }

    @Test
    void listenersMayReturnAMonoOrACompletableFuture() {
        runner.withPropertyValues(SERVICES)
                .withPropertyValues("halyx.subscriptions.billing.listener.max-backoff=PT0.1S",
                        "halyx.stream-consumers.ledger.listener.poll-wait=PT0.2S")
                .withUserConfiguration(Listeners.class).run(context -> {
                    context.getBean("order-eventsReactive", ReactivePublisher.class).publish("ok").block();
                    context.getBean("order-eventsReactive", ReactivePublisher.class).publish("fails-once").block();
                    context.getBean("ledger-outReactive", ReactiveStreamProducer.class).send("k", "entry").block();
                    AsyncListeners listeners = context.getBean(AsyncListeners.class);

                    assertThat(listeners.done.await(15, TimeUnit.SECONDS)).as("handled " + listeners.handled).isTrue();
                    assertThat(listeners.handled).containsExactlyInAnyOrder("ok", "fails-once", "ledger:entry");
                    assertThat(listeners.attempts.get()).as("the failed Mono was retried").isEqualTo(2);
                    assertThat(context.getBean("billing", Subscription.class).receive(10, Duration.ofMillis(300)))
                            .as("both acked").isEmpty();
                });
    }

    @Test
    void halyxObservationsJoinTheSubscribersObservation() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        ObservationThreadLocalAccessor.getInstance().setObservationRegistry(registry);
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class, HalyxObservationAutoConfiguration.class,
                        HalyxReactorAutoConfiguration.class))
                .withBean(ObservationRegistry.class, () -> registry)
                .withPropertyValues(SERVICES)
                .run(context -> {
                    ReactiveBlobStore invoices = context.getBean("invoicesReactive", ReactiveBlobStore.class);
                    Observation request = Observation.start("http.server.requests", registry);

                    // WebFlux puts the request's observation in the Reactor context, under this key.
                    invoices.exists(BlobKey.of("k"))
                            .contextWrite(Context.of(ObservationThreadLocalAccessor.KEY, request)).block();
                    request.stop();

                    TestObservationRegistryAssert.assertThat(registry)
                            .hasObservationWithNameEqualTo("halyx.operation")
                            .that().hasParentObservationEqualTo(request);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnReactiveHalyx {
        @Bean(destroyMethod = "close")
        ReactiveHalyx myReactiveHalyx(Halyx halyx) {
            return ReactiveHalyx.builder(halyx).scheduler(Schedulers.boundedElastic()).build();
        }
    }

    @Test
    void viewsUseTheApplicationsOwnReactiveHalyxWhateverItsName() {
        runner.withPropertyValues(SERVICES).withUserConfiguration(OwnReactiveHalyx.class).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(HalyxReactorAutoConfiguration.REACTIVE_HALYX_BEAN);
            ReactiveBlobStore invoices = context.getBean("invoicesReactive", ReactiveBlobStore.class);
            invoices.put(BlobKey.of("k"), BlobContent.ofString("v")).block(Duration.ofSeconds(5));
            assertThat(context.getBean("invoices", BlobStore.class).getBytes(BlobKey.of("k"))).asString().isEqualTo("v");
        });
    }

    @Test
    void aCachesReactiveViewHasItsCodecsValueType() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                        "halyx.caches.raw.endpoint=redis://localhost:6379", "halyx.caches.raw.codec=bytes")
                .run(context -> {
                    var view = context.getBeanProvider(ResolvableType.forClassWithGenerics(ReactiveCache.class, byte[].class))
                            .getObject();
                    assertThat(view).isSameAs(context.getBean("rawReactive"));
                });
    }
}
