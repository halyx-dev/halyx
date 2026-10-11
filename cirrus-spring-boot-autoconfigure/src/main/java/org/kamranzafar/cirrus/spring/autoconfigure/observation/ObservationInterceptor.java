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
package org.kamranzafar.cirrus.spring.autoconfigure.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.kamranzafar.cirrus.interceptor.Interceptor;
import org.kamranzafar.cirrus.interceptor.Invocation;
import org.kamranzafar.cirrus.interceptor.OperationContext;

/**
 * Records every Cirrus operation as a Micrometer {@link Observation} named {@code cirrus.operation},
 * which yields a timer and, with tracing enabled, a span.
 *
 * <p>Low-cardinality keys (metric tags): {@code cirrus.service}, {@code cirrus.operation},
 * {@code cirrus.provider}, plus {@code error} on failure. The resource ({@code bucket/key}) is a
 * high-cardinality key, attached to spans only.
 */
public final class ObservationInterceptor implements Interceptor {

    public static final String OBSERVATION_NAME = "cirrus.operation";

    private final ObservationRegistry registry;

    public ObservationInterceptor(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <T> T intercept(OperationContext context, Invocation<T> next) {
        return Observation.createNotStarted(OBSERVATION_NAME, registry)
                .contextualName(context.service() + " " + context.operation())
                .lowCardinalityKeyValue("cirrus.service", context.service())
                .lowCardinalityKeyValue("cirrus.operation", context.operation())
                .lowCardinalityKeyValue("cirrus.provider", context.provider())
                .highCardinalityKeyValue("cirrus.resource", context.resource())
                .observe(next::proceed);
    }
}
