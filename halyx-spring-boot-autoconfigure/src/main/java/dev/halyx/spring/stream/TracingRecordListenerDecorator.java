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

import dev.halyx.micrometer.HalyxTracing;
import dev.halyx.stream.RecordListener;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Runs each listener invocation in a consumer span continuing the trace carried in the record's headers; see
 * {@link HalyxTracing#recordListener}.
 */
public final class TracingRecordListenerDecorator implements RecordListenerDecorator {

    private final HalyxTracing tracing;

    public TracingRecordListenerDecorator(Tracer tracer, Propagator propagator) {
        this.tracing = new HalyxTracing(tracer, propagator);
    }

    @Override
    public RecordListener decorate(String consumer, RecordListener listener) {
        return tracing.recordListener(consumer, listener);
    }
}
