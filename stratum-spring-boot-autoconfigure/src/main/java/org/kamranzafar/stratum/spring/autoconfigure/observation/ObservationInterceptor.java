package org.kamranzafar.stratum.spring.autoconfigure.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.kamranzafar.stratum.interceptor.Interceptor;
import org.kamranzafar.stratum.interceptor.Invocation;
import org.kamranzafar.stratum.interceptor.OperationContext;

/**
 * Records every Stratum operation as a Micrometer {@link Observation} named {@code stratum.operation},
 * which yields a timer and, with tracing enabled, a span.
 *
 * <p>Low-cardinality keys (metric tags): {@code stratum.service}, {@code stratum.operation},
 * {@code stratum.provider}, plus {@code error} on failure. The resource ({@code bucket/key}) is a
 * high-cardinality key, attached to spans only.
 */
public final class ObservationInterceptor implements Interceptor {

    public static final String OBSERVATION_NAME = "stratum.operation";

    private final ObservationRegistry registry;

    public ObservationInterceptor(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public <T> T intercept(OperationContext context, Invocation<T> next) {
        return Observation.createNotStarted(OBSERVATION_NAME, registry)
                .contextualName(context.service() + " " + context.operation())
                .lowCardinalityKeyValue("stratum.service", context.service())
                .lowCardinalityKeyValue("stratum.operation", context.operation())
                .lowCardinalityKeyValue("stratum.provider", context.provider())
                .highCardinalityKeyValue("stratum.resource", context.resource())
                .observe(next::proceed);
    }
}
