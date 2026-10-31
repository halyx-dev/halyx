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
package dev.halyx.micrometer;

import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.capability.Capabilities;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.CloudException;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.events.EventPublisher;
import dev.halyx.functions.DefaultFunctionInvoker;
import dev.halyx.functions.FunctionCapability;
import dev.halyx.functions.FunctionConfig;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.functions.spi.AbstractFunctionInvokerDriver;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.memory.InMemoryFunctions;
import dev.halyx.messaging.MessageListener;
import dev.halyx.messaging.Publisher;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.Subscription;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.storage.BlobContent;
import dev.halyx.storage.BlobKey;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Observations and trace propagation in a plain Java application, without Spring. */
class HalyxTracingTest {

    private final SimpleTracer tracer = new SimpleTracer();
    private final HalyxTracing tracing = new HalyxTracing(tracer, new W3cLikePropagator(tracer));
    private Halyx halyx;

    @AfterEach
    void close() {
        if (halyx != null) {
            halyx.close();
        }
    }

    private CloudContext memory() {
        halyx = Halyx.builder().register("local", ProviderConfig.of("memory")).build();
        return halyx.provider("local");
    }

    @Test
    void theInterceptorObservesEveryOperation() {
        TestObservationRegistry registry = TestObservationRegistry.create();
        halyx = Halyx.builder().interceptor(new ObservationInterceptor(registry))
                .register("local", ProviderConfig.of("memory")).build();

        halyx.provider("local").blobStore("docs").put(BlobKey.of("k"), BlobContent.ofString("v"));

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo(ObservationInterceptor.OBSERVATION_NAME)
                .that()
                .hasLowCardinalityKeyValue("halyx.operation", "put")
                .hasLowCardinalityKeyValue("halyx.provider", "memory")
                .hasHighCardinalityKeyValue("halyx.resource", "docs/k")
                .hasBeenStopped();
    }

    @Test
    void theInterceptorObservesAsyncOperationsUntilTheyComplete() throws Exception {
        TestObservationRegistry registry = TestObservationRegistry.create();
        halyx = Halyx.builder().interceptor(new ObservationInterceptor(registry))
                .register("local", ProviderConfig.of("memory")).build();

        halyx.provider("local").blobStore("docs").async().put(BlobKey.of("k"), BlobContent.ofString("v"))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(halyx.provider("local").blobStore("docs").async().getBytes(BlobKey.of("missing")))
                .failsWithin(5, java.util.concurrent.TimeUnit.SECONDS);

        TestObservationRegistryAssert.assertThat(registry)
                .hasObservationWithNameEqualTo(ObservationInterceptor.OBSERVATION_NAME)
                .that()
                .hasLowCardinalityKeyValue("halyx.operation", "put")
                .hasBeenStopped();
        TestObservationRegistryAssert.assertThat(registry)
                .hasNumberOfObservationsWithNameEqualTo(ObservationInterceptor.OBSERVATION_NAME, 2)
                .hasAnObservation(observation -> observation.hasNameEqualTo(ObservationInterceptor.OBSERVATION_NAME)
                        .hasLowCardinalityKeyValue("halyx.operation", "get")
                        .hasError());
    }

    @Test
    void messagesCarryTheTraceToTheListener() throws Exception {
        CloudContext context = memory();
        Subscription billing = context.subscription(SubscriptionConfig.builder("billing").topic("orders").build());
        Publisher orders = tracing.publisher(context.publisher("orders"));

        String traceId = inSpan(() -> orders.publish("order 1"));

        assertThat(traceIdSeenBy(billing)).isEqualTo(traceId);
    }

    @Test
    void eventsCarryTheTraceInTheirExtensions() throws Exception {
        CloudContext context = memory();
        Subscription inbox = context.subscription(SubscriptionConfig.builder("inbox").topic("bus").build());
        EventPublisher bus = tracing.eventPublisher(context.eventPublisher("bus"));

        String traceId = inSpan(() -> bus.publish(CloudEvent.builder("order.placed", "/orders").json("{}").build()));

        ReceivedMessage received = inbox.receive(1, Duration.ofSeconds(5)).getFirst();
        assertThat(received.attributes()).doesNotContainKey("traceparent");
        assertThat(CloudEvents.decode(received).extension("traceparent")).isPresent();
        assertThat(traceIdSeenBy(received)).isEqualTo(traceId);
    }

    @Test
    void theCallersOwnExtensionsAndAttributesWin() {
        CloudContext context = memory();
        Subscription inbox = context.subscription(SubscriptionConfig.builder("inbox").topic("bus").build());
        EventPublisher bus = tracing.eventPublisher(context.eventPublisher("bus"));

        inSpan(() -> bus.publish(CloudEvent.builder("t", "/s").extension("traceparent", "mine").build()));

        assertThat(CloudEvents.decode(inbox.receive(1, Duration.ofSeconds(5)).getFirst()).extension("traceparent"))
                .hasValue("mine");
    }

    @Test
    void functionInvocationsCarryTheTraceInTheirHeaders() {
        List<Map<String, String>> received = new ArrayList<>();
        FunctionInvoker resize = tracing.functionInvoker(echo(memory(), "resize", received));

        String traceId = inSpan(() -> resize.invoke(FunctionRequest.of("{}")));
        String asyncTraceId = inSpan(() -> resize.async().invoke(FunctionRequest.of("{}")).join());

        assertThat(received).extracting(headers -> headers.get("traceparent").split("-")[0])
                .containsExactly(traceId, asyncTraceId);
    }

    @Test
    void theCallersOwnHeadersWin() {
        List<Map<String, String>> received = new ArrayList<>();
        FunctionInvoker resize = tracing.functionInvoker(echo(memory(), "resize", received));

        inSpan(() -> resize.invoke(FunctionRequest.builder("{}").header("traceparent", "mine").build()));

        assertThat(received.getFirst()).containsEntry("traceparent", "mine");
    }

    @Test
    void aTriggerCarriesTheTraceOnlyWhereItTakesHeaders() throws InterruptedException {
        CountDownLatch ran = new CountDownLatch(1);
        List<Map<String, String>> received = new CopyOnWriteArrayList<>();
        FunctionInvoker withHeaders = tracing.functionInvoker(memory().functionInvoker("audit"));
        withHeaders.unwrap(InMemoryFunctions.class).register("audit", request -> {
            received.add(request.headers());
            ran.countDown();
            return FunctionResponse.of("");
        });
        TriggerOnly driver = new TriggerOnly();
        FunctionInvoker withoutHeaders = tracing.functionInvoker(new DefaultFunctionInvoker(driver,
                FunctionConfig.of("audit"), InterceptorChain.empty()));

        inSpan(() -> withHeaders.trigger(FunctionRequest.of("{}")));
        inSpan(() -> withoutHeaders.trigger(FunctionRequest.of("{}")));
        inSpan(() -> withoutHeaders.async().trigger(FunctionRequest.of("{}")).join());

        assertThat(driver.triggered).hasSize(2).allSatisfy(request -> assertThat(request.headers()).isEmpty());
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(received.getFirst()).containsKey("traceparent");
    }

    @Test
    void wrappingTwiceWrapsOnce() {
        CloudContext context = memory();
        Publisher once = tracing.publisher(context.publisher("orders"));

        assertThat(tracing.publisher(once)).isSameAs(once);
    }

    /** A memory function that records the headers it was invoked with. */
    private static FunctionInvoker echo(CloudContext context, String name, List<Map<String, String>> received) {
        FunctionInvoker invoker = context.functionInvoker(name);
        invoker.unwrap(InMemoryFunctions.class).register(name, request -> {
            synchronized (received) {
                received.add(request.headers());
            }
            return FunctionResponse.of(request.payload());
        });
        return invoker;
    }

    private String inSpan(Runnable work) {
        Span request = tracer.nextSpan().name("request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            work.run();
        } finally {
            request.end();
        }
        return request.context().traceId();
    }

    private String traceIdSeenBy(Subscription subscription) throws Exception {
        return traceIdSeenBy(subscription.receive(1, Duration.ofSeconds(5)).getFirst());
    }

    private String traceIdSeenBy(ReceivedMessage message) throws Exception {
        List<String> seen = new ArrayList<>();
        MessageListener listener = tracing.listener("billing",
                m -> seen.add(tracer.currentTraceContext().context().traceId()));
        listener.onMessage(message);
        return seen.getFirst();
    }

    /** A provider that triggers functions but takes no headers on a trigger, as Lambda. */
    private static final class TriggerOnly extends AbstractFunctionInvokerDriver {

        final List<FunctionRequest> triggered = new CopyOnWriteArrayList<>();

        TriggerOnly() {
            super("fake");
        }

        @Override
        public String function() {
            return "audit";
        }

        @Override
        public FunctionResponse invoke(FunctionRequest request) {
            return FunctionResponse.of(request.payload());
        }

        @Override
        public void trigger(FunctionRequest request) {
            triggered.add(request);
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(FunctionCapability.TRIGGER);
        }

        @Override
        public long maxPayloadBytes() {
            return 1024;
        }

        @Override
        protected Optional<CloudException> translateNative(Throwable error) {
            return Optional.empty();
        }

        @Override
        protected List<Object> nativeClients() {
            return List.of();
        }
    }

    /** Carries the trace and span ids in one {@code traceparent} field, enough to check propagation end to end. */
    static final class W3cLikePropagator implements Propagator {
        private final SimpleTracer tracer;

        W3cLikePropagator(SimpleTracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public List<String> fields() {
            return List.of("traceparent");
        }

        @Override
        public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
            setter.set(carrier, "traceparent", context.traceId() + "-" + context.spanId());
        }

        @Override
        public <C> Span.Builder extract(C carrier, Getter<C> getter) {
            String value = getter.get(carrier, "traceparent");
            if (value == null || !value.contains("-")) {
                return tracer.spanBuilder();
            }
            String[] ids = value.split("-");
            return tracer.spanBuilder().setParent(tracer.traceContextBuilder().traceId(ids[0]).spanId(ids[1]).build());
        }
    }
}
