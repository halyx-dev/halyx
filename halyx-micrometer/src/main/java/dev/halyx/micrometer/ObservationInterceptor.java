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

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Records every Halyx operation as a Micrometer {@link Observation} named {@code halyx.operation},
 * which yields a timer and, with tracing enabled, a span.
 *
 * <p>Low-cardinality keys (metric tags): {@code halyx.service}, {@code halyx.operation},
 * {@code halyx.provider}, plus {@code error} on failure. The resource ({@code bucket/key}) is a
 * high-cardinality key, attached to spans only.
 *
 * <p>Add it first, so the observation covers retries and every other interceptor:
 * {@code Halyx.builder().interceptor(new ObservationInterceptor(registry))}. The Spring Boot starter does this for
 * the application's {@code ObservationRegistry}.
 */
public final class ObservationInterceptor implements Interceptor {

    public static final String OBSERVATION_NAME = "halyx.operation";

    private final ObservationRegistry registry;

    public ObservationInterceptor(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <T> T intercept(OperationContext context, Invocation<T> next) {
        return observation(context).observe(next::proceed);
    }

    /** Observes from the call until its future completes, with no thread waiting in between. */
    @Override
    public <T> CompletableFuture<T> interceptAsync(OperationContext context, AsyncInvocation<T> next, Executor blocking) {
        Observation observation = observation(context).start();
        CompletableFuture<T> call;
        try (Observation.Scope ignored = observation.openScope()) {
            call = next.proceed();
        } catch (RuntimeException | Error e) {
            observation.error(e);
            observation.stop();
            throw e;
        }
        return call.whenComplete((value, error) -> {
            if (error != null) {
                observation.error(AsyncInvocation.unwrap(error));
            }
            observation.stop();
        });
    }

    private Observation observation(OperationContext context) {
        return Observation.createNotStarted(OBSERVATION_NAME, registry)
                .contextualName(context.service() + " " + context.operation())
                .lowCardinalityKeyValue("halyx.service", context.service())
                .lowCardinalityKeyValue("halyx.operation", context.operation())
                .lowCardinalityKeyValue("halyx.provider", context.provider())
                .highCardinalityKeyValue("halyx.resource", context.resource());
    }
}
