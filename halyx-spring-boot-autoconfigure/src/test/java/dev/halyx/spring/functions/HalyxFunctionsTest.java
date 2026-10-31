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
package dev.halyx.spring.functions;

import dev.halyx.error.UnavailableException;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import dev.halyx.memory.InMemoryFunctions;
import dev.halyx.micrometer.TracingFunctionInvoker;
import dev.halyx.spring.autoconfigure.HalyxAutoConfiguration;
import dev.halyx.spring.autoconfigure.observation.HalyxMessagingTracingAutoConfiguration;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.micrometer.tracing.test.simple.SimpleTracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Function invokers from properties, on the in-memory provider, on Boot 3.5 and 4. */
class HalyxFunctionsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HalyxAutoConfiguration.class))
            .withPropertyValues(
                    "halyx.providers.local.type=memory",
                    "halyx.functions.resize.name=resize-image",
                    "halyx.functions.resize.timeout=200ms",
                    "halyx.functions.resize.idempotent=true",
                    "halyx.functions.resize.headers.X-App=orders",
                    "halyx.functions.resize.properties.aws.lambda.qualifier=live",
                    "halyx.functions.audit.provider=local");

    @Test
    void invokersFromProperties() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            FunctionInvoker resize = context.getBean("resize", FunctionInvoker.class);
            assertThat(resize.function()).isEqualTo("resize-image");
            assertThat(context.getBean("audit", FunctionInvoker.class).function()).isEqualTo("audit");

            AtomicReference<FunctionRequest> received = new AtomicReference<>();
            resize.unwrap(InMemoryFunctions.class).register("resize-image", request -> {
                received.set(request);
                return FunctionResponse.of(request.payload());
            });

            FunctionResponse response = resize.invoke(FunctionRequest.builder("{\"key\":\"a.png\"}")
                    .header("X-Tenant", "acme").build());

            assertThat(response.payloadAsString()).isEqualTo("{\"key\":\"a.png\"}");
            assertThat(received.get().headers()).containsExactlyInAnyOrderEntriesOf(
                    Map.of("X-App", "orders", "X-Tenant", "acme"));
        });
    }

    @Test
    void theConfiguredTimeoutApplies() {
        runner.run(context -> {
            FunctionInvoker resize = context.getBean("resize", FunctionInvoker.class);
            resize.unwrap(InMemoryFunctions.class).register("resize-image", request -> {
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return FunctionResponse.of("late");
            });

            assertThatThrownBy(() -> resize.invoke(FunctionRequest.of("{}"))).isInstanceOf(UnavailableException.class)
                    .hasMessageContaining("PT0.2S");
        });
    }

    @Test
    void invocationsCarryTheTraceToTheFunction() {
        SimpleTracer tracer = new SimpleTracer();
        runner.withConfiguration(AutoConfigurations.of(HalyxMessagingTracingAutoConfiguration.class))
                .withBean(Tracer.class, () -> tracer)
                .withBean(Propagator.class, () -> new TraceparentPropagator(tracer))
                .run(context -> {
                    FunctionInvoker resize = context.getBean("resize", FunctionInvoker.class);
                    assertThat(resize).isInstanceOf(TracingFunctionInvoker.class);
                    AtomicReference<String> traceparent = new AtomicReference<>();
                    resize.unwrap(InMemoryFunctions.class).register("resize-image", request -> {
                        traceparent.set(request.headers().get("traceparent"));
                        return FunctionResponse.of("");
                    });

                    Span request = tracer.nextSpan().name("http request").start();
                    try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
                        resize.invoke(FunctionRequest.of("{}"));
                    } finally {
                        request.end();
                    }

                    assertThat(traceparent.get()).startsWith(request.context().traceId() + "-");
                });
    }

    /** Carries the trace and span ids in one {@code traceparent} field. */
    static final class TraceparentPropagator implements Propagator {
        private final SimpleTracer tracer;

        TraceparentPropagator(SimpleTracer tracer) {
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
            return tracer.spanBuilder();
        }
    }
}
