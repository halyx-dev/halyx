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
package dev.halyx.spring.stream;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.metrics.HalyxCacheMetricsAutoConfiguration;
import dev.halyx.spring.autoconfigure.observation.HalyxMessagingTracingAutoConfiguration;
import dev.halyx.spring.messaging.HalyxListener;
import dev.halyx.spring.messaging.HalyxListenerRegistrar;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.ReceivedRecord;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamListener;
import dev.halyx.stream.StreamProducer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HalyxStreamTest {

    private static final String[] MEMORY = {
            "halyx.providers.local.type=memory",
            "halyx.blob-stores.claims.bucket=claims",
            "halyx.stream-clusters.events.bootstrap-servers=memory:9092",
            "halyx.stream-producers.orders-out.topic=orders",
            "halyx.stream-producers.orders-dlt.topic=orders-dlt",
            "halyx.stream-consumers.orders.group=billing",
            "halyx.stream-consumers.orders.start-position=earliest",
            "halyx.stream-consumers.orders.listener.poll-wait=PT0.2S",
            "halyx.stream-consumers.orders.listener.initial-backoff=PT0.05S",
            "halyx.stream-consumers.orders.listener.max-attempts=3",
            "halyx.stream-consumers.orders.listener.dead-letter=orders-dlt"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class));

    static class Orders {
        final List<String> handled = new CopyOnWriteArrayList<>();
        final List<String> traceIds = new CopyOnWriteArrayList<>();
        Tracer tracer;

        @HalyxListener("orders")
        void onOrder(ReceivedRecord record) {
            if (record.valueAsString().equals("poison")) {
                throw new IllegalStateException("cannot bill");
            }
            if (record.valueAsString().equals("flaky") && record.deliveryAttempt() < 2) {
                throw new IllegalStateException("try again");
            }
            if (tracer != null && tracer.currentSpan() != null) {
                traceIds.add(tracer.currentSpan().context().traceId());
            }
            handled.add(record.key().orElse("") + "=" + record.valueAsString());
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OrdersConfig {
        @Bean
        Orders orderHandler() {
            return new Orders();
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as(what).isBefore(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void producerAndConsumerBeansComeFromProperties() {
        runner.withPropertyValues(MEMORY).run(context -> {
            StreamProducer producer = context.getBean("orders-out", StreamProducer.class);
            StreamConsumer consumer = context.getBean("orders", StreamConsumer.class);

            assertThat(producer.topic()).isEqualTo("orders");
            assertThat(producer.provider()).isEqualTo("memory");
            assertThat(consumer.topic()).isEqualTo("orders");
            assertThat(consumer.group()).isEqualTo("billing");
        });
    }

    @Test
    void topicAndGroupDefaultToTheBeanName() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                "halyx.stream-clusters.events.bootstrap-servers=memory:9092",
                "halyx.stream-producers.audit-events.idempotent=true",
                "halyx.stream-consumers.audit.start-position=earliest").run(context -> {
            assertThat(context.getBean("audit-events", StreamProducer.class).topic()).isEqualTo("audit-events");
            assertThat(context.getBean("audit", StreamConsumer.class).group()).isEqualTo("audit");
            assertThat(context.getBean("audit", StreamConsumer.class).topic()).isEqualTo("audit");
        });
    }

    @Test
    void listenerHandlesRecordsRetriesAndDeadLetters() {
        runner.withPropertyValues(MEMORY).withUserConfiguration(OrdersConfig.class).run(context -> {
            StreamProducer producer = context.getBean("orders-out", StreamProducer.class);
            producer.sendAll(List.of(OutgoingRecord.of("c1", "one"), OutgoingRecord.of("c1", "flaky"),
                    OutgoingRecord.of("c2", "poison"), OutgoingRecord.of("c2", "after-poison")));

            Orders orders = context.getBean(Orders.class);
            await("records handled", () -> orders.handled.size() == 3);
            assertThat(orders.handled).containsExactlyInAnyOrder("c1=one", "c1=flaky", "c2=after-poison");
            assertThat(orders.handled.indexOf("c1=one")).isLessThan(orders.handled.indexOf("c1=flaky"));

            StreamListener listener = context.getBean(HalyxListenerRegistrar.class).streamListeners()
                    .get("orders#orderHandler.onOrder");
            assertThat(listener.isRunning()).isTrue();
            await("dead-lettered", () -> listener.statistics().deadLettered() == 1);
            assertThat(listener.statistics().failed()).isEqualTo(4); // flaky once, poison three times
        });
    }

    @Test
    void listenerMetricsAreRegistered() {
        runner.withConfiguration(AutoConfigurations.of(HalyxCacheMetricsAutoConfiguration.class))
                .withPropertyValues(MEMORY).withUserConfiguration(OrdersConfig.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    context.getBean("orders-out", StreamProducer.class).sendAll(List.of(OutgoingRecord.of("c1", "one"),
                            OutgoingRecord.of("c1", "flaky")));
                    Orders orders = context.getBean(Orders.class);
                    await("records handled", () -> orders.handled.size() == 2);
                    MeterRegistry registry = context.getBean(MeterRegistry.class);

                    await("handled counted", () -> registry.get("halyx.stream.records").tag("consumer", "orders")
                            .tag("result", "handled").functionCounter().count() == 2);
                    assertThat(registry.get("halyx.stream.records").tag("result", "failed").functionCounter().count())
                            .isEqualTo(1);
                    assertThat(registry.get("halyx.stream.assigned.partitions").gauge().value()).isEqualTo(3);
                    assertThat(registry.find("halyx.messaging.messages").meters()).isEmpty();
                });
    }

    @Test
    void deadLettersArriveOnTheDeadLetterTopic() {
        runner.withPropertyValues(MEMORY)
                .withPropertyValues("halyx.stream-consumers.dlt-reader.topic=orders-dlt",
                        "halyx.stream-consumers.dlt-reader.start-position=earliest")
                .withUserConfiguration(OrdersConfig.class).run(context -> {
                    context.getBean("orders-out", StreamProducer.class).send("c9", "poison");
                    StreamConsumer reader = context.getBean("dlt-reader", StreamConsumer.class);

                    List<ReceivedRecord> letters = new ArrayList<>();
                    Instant deadline = Instant.now().plusSeconds(15);
                    while (letters.isEmpty() && Instant.now().isBefore(deadline)) {
                        letters.addAll(reader.poll(Duration.ofMillis(200)));
                    }

                    assertThat(letters).hasSize(1);
                    assertThat(letters.getFirst().valueAsString()).isEqualTo("poison");
                    assertThat(letters.getFirst().headers()).containsEntry("halyx-dead-letter-topic", "orders")
                            .hasEntrySatisfying("halyx-dead-letter-error", e -> assertThat(e).contains("cannot bill"));
                });
    }

    @Test
    void claimChecksGoThroughTheNamedBlobStore() {
        runner.withPropertyValues(MEMORY)
                .withPropertyValues("halyx.stream-producers.orders-out.claim-check.blob-store=claims",
                        "halyx.stream-producers.orders-out.claim-check.threshold=10",
                        "halyx.stream-consumers.orders.claim-check.blob-store=claims",
                        "halyx.stream-consumers.orders.claim-check.threshold=10")
                .withUserConfiguration(OrdersConfig.class).run(context -> {
                    String large = "x".repeat(1000);
                    context.getBean("orders-out", StreamProducer.class).send("big", large);

                    Orders orders = context.getBean(Orders.class);
                    await("large record handled", () -> !orders.handled.isEmpty());
                    assertThat(orders.handled).containsExactly("big=" + large);
                });
    }

    @Test
    void traceContextTravelsInRecordHeaders() {
        SimpleTracer tracer = new SimpleTracer();
        Propagator propagator = new Propagator() {
            @Override
            public List<String> fields() {
                return List.of("trace-id");
            }

            @Override
            public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
                setter.set(carrier, "trace-id", context.traceId());
            }

            @Override
            public <C> Span.Builder extract(C carrier, Getter<C> getter) {
                String traceId = getter.get(carrier, "trace-id");
                Span.Builder builder = tracer.spanBuilder();
                if (traceId != null) {
                    builder.setParent(tracer.traceContextBuilder().traceId(traceId).spanId("0000000000000001").build());
                }
                return builder;
            }
        };
        runner.withConfiguration(AutoConfigurations.of(HalyxMessagingTracingAutoConfiguration.class))
                .withBean(Tracer.class, () -> tracer).withBean(Propagator.class, () -> propagator)
                .withPropertyValues(MEMORY).withUserConfiguration(OrdersConfig.class).run(context -> {
                    Orders orders = context.getBean(Orders.class);
                    orders.tracer = tracer;
                    Span span = tracer.nextSpan().name("checkout").start();
                    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                        context.getBean("orders-out", StreamProducer.class).send("c1", "traced");
                    } finally {
                        span.end();
                    }

                    await("traced record handled", () -> !orders.traceIds.isEmpty());
                    assertThat(context.getBean("orders-out", StreamProducer.class)).isInstanceOf(TracingStreamProducer.class);
                    assertThat(orders.traceIds).containsExactly(span.context().traceId());
                    assertThat(tracer.getSpans()).anyMatch(s -> s.getName().equals("halyx.record orders"));
                });
    }

    @Test
    void producersAndConsumersNeedAKnownCluster() {
        runner.withPropertyValues("halyx.providers.local.type=memory",
                "halyx.stream-clusters.a.bootstrap-servers=memory:9092",
                "halyx.stream-clusters.b.bootstrap-servers=memory:9093",
                "halyx.stream-producers.orders.topic=orders").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("halyx.stream-producers.orders.cluster is required"));

        runner.withPropertyValues("halyx.providers.local.type=memory",
                "halyx.stream-clusters.a.bootstrap-servers=memory:9092",
                "halyx.stream-consumers.orders.cluster=missing").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("unknown stream cluster 'missing'"));

        runner.withPropertyValues("halyx.providers.local.type=memory",
                "halyx.stream-clusters.a.auth.type=iam",
                "halyx.stream-producers.orders.topic=orders").run(context ->
                assertThat(context).hasFailed().getFailure()
                        .hasStackTraceContaining("halyx.stream-clusters.a.bootstrap-servers is required"));
    }

    @Test
    void aDeadLetterMustNameAStreamProducer() {
        runner.withPropertyValues(MEMORY)
                .withPropertyValues("halyx.stream-consumers.orders.listener.dead-letter=nowhere")
                .withUserConfiguration(OrdersConfig.class).run(context ->
                        assertThat(context).hasFailed().getFailure()
                                .hasStackTraceContaining("dead-letter must name one of halyx.stream-producers"));
    }

    @Test
    void consumersLeaveTheirGroupWhenTheContextCloses() {
        AtomicReference<StreamConsumer> consumer = new AtomicReference<>();
        runner.withPropertyValues(MEMORY).run(context -> {
            consumer.set(context.getBean("orders", StreamConsumer.class));
            consumer.get().poll(Duration.ofMillis(10));
        });

        assertThatThrownBy(() -> consumer.get().poll(Duration.ofMillis(10)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("closed");
    }
}
