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

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import dev.halyx.capability.Capabilities;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.SendResult;
import dev.halyx.stream.StreamProducer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the current trace context to every record sent as headers (W3C {@code traceparent} and {@code tracestate},
 * or B3, whatever the propagator writes), so consumers continue the same trace.
 */
public final class TracingStreamProducer implements StreamProducer {

    private final StreamProducer delegate;
    private final Tracer tracer;
    private final Propagator propagator;

    public TracingStreamProducer(StreamProducer delegate, Tracer tracer, Propagator propagator) {
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
    public SendResult send(OutgoingRecord record) {
        return delegate.send(withTraceContext(record));
    }

    @Override
    public List<SendResult> sendAll(List<OutgoingRecord> records) {
        return delegate.sendAll(records.stream().map(this::withTraceContext).toList());
    }

    private OutgoingRecord withTraceContext(OutgoingRecord record) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return record;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        propagator.inject(context, headers, Map::put);
        headers.keySet().removeAll(record.headers().keySet()); // the caller's own headers win
        return headers.isEmpty() ? record : record.withValue(record.valueUnsafe(), headers);
    }

    @Override
    public int partitionCount() {
        return delegate.partitionCount();
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
