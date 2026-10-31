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

import dev.halyx.capability.Capabilities;
import dev.halyx.functions.AsyncFunctionInvoker;
import dev.halyx.functions.FunctionCapability;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Adds the current trace context to every invocation's headers (W3C {@code traceparent} and {@code tracestate}, or
 * B3, whatever the propagator writes), so the function continues the same trace: HTTP functions get them as request
 * headers, Lambda functions in the {@code ClientContext}. A trigger carries them only where the provider takes
 * headers on one ({@link FunctionCapability#TRIGGER_HEADERS}); on Lambda, a triggered function starts a new trace.
 */
public final class TracingFunctionInvoker implements FunctionInvoker {

    private final FunctionInvoker delegate;
    private final Tracer tracer;
    private final Propagator propagator;

    public TracingFunctionInvoker(FunctionInvoker delegate, Tracer tracer, Propagator propagator) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public String provider() {
        return delegate.provider();
    }

    @Override
    public String function() {
        return delegate.function();
    }

    @Override
    public Capabilities capabilities() {
        return delegate.capabilities();
    }

    @Override
    public FunctionResponse invoke(FunctionRequest request) {
        return delegate.invoke(withTraceContext(request));
    }

    @Override
    public void trigger(FunctionRequest request) {
        delegate.trigger(forTrigger(request));
    }

    /** The delegate's asynchronous view, with the trace context taken on the calling thread, where it is current. */
    @Override
    public AsyncFunctionInvoker async(Executor blocking) {
        AsyncFunctionInvoker async = delegate.async(blocking);
        return new AsyncFunctionInvoker() {
            @Override
            public String provider() {
                return async.provider();
            }

            @Override
            public String function() {
                return async.function();
            }

            @Override
            public CompletableFuture<FunctionResponse> invoke(FunctionRequest request) {
                return async.invoke(withTraceContext(request));
            }

            @Override
            public CompletableFuture<Void> trigger(FunctionRequest request) {
                return async.trigger(forTrigger(request));
            }

            @Override
            public FunctionInvoker blocking() {
                return TracingFunctionInvoker.this;
            }
        };
    }

    /** Headers on a trigger would make it fail where the provider takes none, so the trace context goes only where it may. */
    private FunctionRequest forTrigger(FunctionRequest request) {
        return capabilities().supports(FunctionCapability.TRIGGER_HEADERS) ? withTraceContext(request) : request;
    }

    private FunctionRequest withTraceContext(FunctionRequest request) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return request;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        propagator.inject(context, headers, Map::put);
        headers.keySet().removeAll(request.headers().keySet()); // the caller's own headers win
        return headers.isEmpty() ? request : request.withPayload(request.payloadUnsafe(), headers);
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
