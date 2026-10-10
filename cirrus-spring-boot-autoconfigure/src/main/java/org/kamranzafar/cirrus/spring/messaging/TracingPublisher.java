package org.kamranzafar.cirrus.spring.messaging;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.kamranzafar.cirrus.capability.Capabilities;
import org.kamranzafar.cirrus.messaging.OutgoingMessage;
import org.kamranzafar.cirrus.messaging.PublishResult;
import org.kamranzafar.cirrus.messaging.Publisher;

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
