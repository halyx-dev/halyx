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
package org.kamranzafar.halyx.spring.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.kamranzafar.halyx.messaging.OutgoingMessage;
import org.kamranzafar.halyx.messaging.Publisher;
import org.kamranzafar.halyx.messaging.ReceivedMessage;
import org.kamranzafar.halyx.messaging.Subscription;
import org.kamranzafar.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import org.kamranzafar.halyx.spring.autoconfigure.metrics.HalyxCacheMetricsAutoConfiguration;
import org.kamranzafar.halyx.spring.autoconfigure.observation.HalyxMessagingTracingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class HalyxMessagingTest {

    private static final String[] MEMORY = {
            "halyx.providers.local.type=memory",
            "halyx.blob-stores.claims.bucket=claims",
            "halyx.topics.orders-out.name=orders",
            "halyx.subscriptions.orders.name=orders-sub",
            "halyx.subscriptions.orders.topic=orders",
            "halyx.subscriptions.orders.properties.memory.lease=PT1S",
            "halyx.subscriptions.orders.listener.concurrency=4",
            "halyx.subscriptions.orders.listener.poll-wait=PT1S",
            "halyx.subscriptions.orders.listener.initial-backoff=PT0.1S"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class));

    static class Orders {
        final Set<String> handled = ConcurrentHashMap.newKeySet();
        final AtomicInteger failuresLeft = new AtomicInteger(1);
        final CountDownLatch latch = new CountDownLatch(3);
        final List<String> traceIds = new CopyOnWriteArrayList<>();
        Tracer tracer;

        @HalyxListener("orders")
        void onOrder(String body) {
            if (body.equals("flaky") && failuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("try again");
            }
            if (tracer != null && tracer.currentSpan() != null) {
                traceIds.add(tracer.currentSpan().context().traceId());
            }
            if (handled.add(body)) {
                latch.countDown();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrdersConfig {
        @Bean
        Orders orderHandler() {
            return new Orders();
        }
    }

    @Test
    void listenerReceivesPublishedMessagesAndRetriesFailures() {
        runner.withPropertyValues(MEMORY).withUserConfiguration(OrdersConfig.class).run(context -> {
            Publisher publisher = context.getBean("orders-out", Publisher.class);
            assertThat(context.getBean("orders", Subscription.class).name()).isEqualTo("orders-sub");

            publisher.publish("one");
            publisher.publish("flaky");
            publisher.publish(OutgoingMessage.builder("three").attribute("k", "v").build());

            Orders orders = context.getBean(Orders.class);
            assertThat(orders.latch.await(15, TimeUnit.SECONDS)).isTrue();
            assertThat(orders.handled).containsExactlyInAnyOrder("one", "flaky", "three");
            var consumer = context.getBean(HalyxListenerRegistrar.class).consumers().get("orders#orderHandler.onOrder");
            assertThat(consumer.isRunning()).isTrue();
            assertThat(consumer.statistics().failed()).isEqualTo(1);
        });
    }

    @Test
    void claimChecksGoThroughTheNamedBlobStore() {
        runner.withPropertyValues(MEMORY)
                .withPropertyValues("halyx.topics.orders-out.claim-check.blob-store=claims",
                        "halyx.topics.orders-out.claim-check.threshold=10",
                        "halyx.subscriptions.orders.claim-check.blob-store=claims",
                        "halyx.subscriptions.orders.claim-check.threshold=10")
                .run(context -> {
                    context.getBean("orders-out", Publisher.class).publish("a body longer than ten bytes");
                    List<ReceivedMessage> received = context.getBean("orders", Subscription.class).receive(1, Duration.ofSeconds(5));

                    assertThat(received).singleElement().satisfies(m -> {
                        assertThat(m.bodyAsString()).isEqualTo("a body longer than ten bytes");
                        m.ack();
                    });
                    assertThat(context.getBean("claims", org.kamranzafar.halyx.storage.BlobStore.class)
                            .listAll("claim-check/").count()).isEqualTo(1);
                });
    }

    @Test
    void listenerMetricsAreRegistered() {
        runner.withConfiguration(AutoConfigurations.of(HalyxCacheMetricsAutoConfiguration.class))
                .withPropertyValues(MEMORY).withUserConfiguration(OrdersConfig.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    context.getBean("orders-out", Publisher.class).publish("one");
                    context.getBean("orders-out", Publisher.class).publish("flaky");
                    context.getBean("orders-out", Publisher.class).publish("three");
                    assertThat(context.getBean(Orders.class).latch.await(15, TimeUnit.SECONDS)).isTrue();
                    MeterRegistry registry = context.getBean(MeterRegistry.class);
                    Thread.sleep(200);

                    assertThat(registry.get("halyx.messaging.messages").tag("subscription", "orders")
                            .tag("result", "acked").functionCounter().count()).isGreaterThanOrEqualTo(3);
                    assertThat(registry.get("halyx.messaging.messages").tag("result", "failed")
                            .functionCounter().count()).isEqualTo(1);
                    assertThat(registry.get("halyx.messaging.in.flight").gauge().value()).isGreaterThanOrEqualTo(0);
                });
    }

    @Test
    void mistakesFailAtStartupWithTheCauseNamed() {
        runner.withPropertyValues("halyx.providers.local.type=memory")
                .withBean(Orders.class, Orders::new)
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("@HalyxListener(\"orders\")")
                        .hasStackTraceContaining("halyx.subscriptions.orders"));
        runner.withPropertyValues(MEMORY).withPropertyValues("halyx.topics.orders-out.claim-check.blob-store=nope")
                .run(context -> assertThat(context).getFailure()
                        .hasStackTraceContaining("halyx.topics.orders-out.claim-check.blob-store"));
        runner.withPropertyValues(MEMORY).withBean(BadSignature.class, BadSignature::new)
                .run(context -> assertThat(context).getFailure().hasStackTraceContaining("must take one parameter"));
    }

    static class BadSignature {
        @HalyxListener("orders")
        void handle(String body, int extra) {
        }
    }

    /** Carries the trace and span ids in one attribute, enough to check propagation end to end. */
    static final class TestPropagator implements Propagator {
        private final SimpleTracer tracer;

        TestPropagator(SimpleTracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public List<String> fields() {
            return List.of("test-trace");
        }

        @Override
        public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
            setter.set(carrier, "test-trace", context.traceId() + "-" + context.spanId());
        }

        @Override
        public <C> Span.Builder extract(C carrier, Getter<C> getter) {
            String value = getter.get(carrier, "test-trace");
            if (value == null) {
                return tracer.spanBuilder();
            }
            String[] ids = value.split("-");
            return tracer.spanBuilder().setParent(tracer.traceContextBuilder().traceId(ids[0]).spanId(ids[1]).build());
        }
    }

    @Test
    void traceContextTravelsInMessageAttributes() {
        SimpleTracer tracer = new SimpleTracer();
        runner.withConfiguration(AutoConfigurations.of(HalyxMessagingTracingAutoConfiguration.class))
                .withPropertyValues(MEMORY).withUserConfiguration(OrdersConfig.class)
                .withBean(Tracer.class, () -> tracer)
                .withBean(Propagator.class, () -> new TestPropagator(tracer))
                .run(context -> {
                    Orders orders = context.getBean(Orders.class);
                    orders.tracer = tracer;
                    Publisher publisher = context.getBean("orders-out", Publisher.class);
                    assertThat(publisher).isInstanceOf(TracingPublisher.class);

                    Span request = tracer.nextSpan().name("http request").start();
                    try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
                        publisher.publishAll(List.of(OutgoingMessage.of("one"), OutgoingMessage.of("three")));
                        publisher.publish("flaky");
                    } finally {
                        request.end();
                    }

                    assertThat(orders.latch.await(15, TimeUnit.SECONDS)).isTrue();
                    assertThat(orders.traceIds).isNotEmpty().allMatch(id -> id.equals(request.context().traceId()));
                    assertThat(tracer.getSpans()).anyMatch(s -> s.getName().equals("halyx.message orders"));
                });
    }
}
