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

import dev.halyx.events.CloudEvent;
import dev.halyx.events.EventPublisher;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Adds the current trace context to every published event as extension attributes, as the CloudEvents
 * Distributed Tracing extension does ({@code traceparent} and {@code tracestate} with W3C propagation, {@code b3}
 * with single-header B3), so the listener on the router's queue continues the same trace. Fields that can't be
 * extension names, such as multi-header B3's {@code X-B3-TraceId}, are left out.
 */
public final class TracingEventPublisher implements EventPublisher {

    private static final Pattern EXTENSION_NAME = Pattern.compile("[a-z0-9]{1,20}");

    private final EventPublisher delegate;
    private final Tracer tracer;
    private final Propagator propagator;

    public TracingEventPublisher(EventPublisher delegate, Tracer tracer, Propagator propagator) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String bus() {
        return delegate.bus();
    }

    @Override
    public void publish(CloudEvent event) {
        delegate.publish(withTraceContext(event));
    }

    @Override
    public void publishAll(List<CloudEvent> events) {
        delegate.publishAll(events.stream().map(this::withTraceContext).toList());
    }

    private CloudEvent withTraceContext(CloudEvent event) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return event;
        }
        Map<String, String> fields = new LinkedHashMap<>();
        propagator.inject(context, fields, (carrier, key, value) -> carrier.put(key.toLowerCase(Locale.ROOT), value));
        CloudEvent.Builder traced = null;
        for (var field : fields.entrySet()) {
            // the caller's own extensions win
            if (EXTENSION_NAME.matcher(field.getKey()).matches() && event.extension(field.getKey()).isEmpty()) {
                traced = (traced == null ? event.toBuilder() : traced).extension(field.getKey(), field.getValue());
            }
        }
        return traced == null ? event : traced.build();
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return delegate.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "Tracing" + delegate;
    }
}
