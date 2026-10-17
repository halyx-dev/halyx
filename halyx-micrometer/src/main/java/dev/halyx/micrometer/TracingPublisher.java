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

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import dev.halyx.capability.Capabilities;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.PublishResult;
import dev.halyx.messaging.Publisher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the current trace context to every published message as attributes (W3C {@code traceparent} and
 * {@code tracestate}, or B3, whatever the propagator writes), so consumers continue the same trace.
 */
public final class TracingPublisher implements Publisher {

    private final Publisher delegate;
    private final Tracer tracer;
    private final Propagator propagator;

    public TracingPublisher(Publisher delegate, Tracer tracer, Propagator propagator) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String topic() {
        return delegate.topic();
    }

    @Override
    public Capabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public PublishResult publish(OutgoingMessage message) {
        return delegate.publish(withTraceContext(message));
    }

    @Override
    public List<PublishResult> publishAll(List<OutgoingMessage> messages) {
        return delegate.publishAll(messages.stream().map(this::withTraceContext).toList());
    }

    private OutgoingMessage withTraceContext(OutgoingMessage message) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return message;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        propagator.inject(context, headers, Map::put);
        headers.keySet().removeAll(message.attributes().keySet()); // the caller's own attributes win
        return headers.isEmpty() ? message : message.withBody(message.bodyUnsafe(), headers);
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
