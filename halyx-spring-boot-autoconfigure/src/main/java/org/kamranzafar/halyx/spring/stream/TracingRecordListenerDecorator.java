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
package org.kamranzafar.halyx.spring.stream;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.kamranzafar.halyx.stream.RecordListener;

/**
 * Runs each listener invocation in a consumer span continuing the trace carried in the record's headers (a new
 * trace when there is none). The span is tagged with the topic, partition, offset and delivery attempt.
 */
public final class TracingRecordListenerDecorator implements RecordListenerDecorator {

    private final Tracer tracer;
    private final Propagator propagator;

    public TracingRecordListenerDecorator(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public RecordListener decorate(String consumer, RecordListener listener) {
        return record -> {
            Span span = propagator.extract(record.headers(), (carrier, key) -> carrier.get(key))
                    .name("halyx.record " + consumer)
                    .kind(Span.Kind.CONSUMER)
                    .tag("messaging.destination.name", record.topic())
                    .tag("messaging.destination.partition.id", String.valueOf(record.partition()))
                    .tag("messaging.kafka.offset", String.valueOf(record.offset()))
                    .tag("messaging.delivery.attempt", String.valueOf(record.deliveryAttempt()))
                    .start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                listener.onRecord(record);
            } catch (Exception | Error e) {
                span.error(e);
                throw e;
            } finally {
                span.end();
            }
        };
    }
}
