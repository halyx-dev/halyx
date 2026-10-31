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
package dev.halyx.reactor;

import dev.halyx.capability.Capabilities;
import dev.halyx.functions.AsyncFunctionInvoker;
import dev.halyx.functions.FunctionInvoker;
import dev.halyx.functions.FunctionRequest;
import dev.halyx.functions.FunctionResponse;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.concurrent.Executor;

/** A {@link FunctionInvoker} as {@code Mono}s, each invoking once per subscription on the application's scheduler. */
public final class ReactiveFunctionInvoker {

    private final FunctionInvoker invoker;
    private final Calls calls;

    ReactiveFunctionInvoker(FunctionInvoker invoker, Calls calls) {
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        this.calls = calls;
    }

    public String provider() {
        return invoker.provider();
    }

    public String function() {
        return invoker.function();
    }

    public Capabilities capabilities() {
        return invoker.capabilities();
    }

    /** The blocking invoker this wraps. */
    public FunctionInvoker blocking() {
        return invoker;
    }

    /**
     * The invoker's asynchronous view for one call, whose blocking work (where the driver has no non-blocking path)
     * runs on {@code blocking}: the scheduler, with the subscriber's context restored.
     */
    private AsyncFunctionInvoker async(Executor blocking) {
        AsyncFunctionInvoker view = invoker.async(blocking);
        return view != null ? view : AsyncFunctionInvoker.onExecutor(invoker, blocking); // a mock or proxy without one
    }

    /** Invokes the function; resubscribing invokes it again, so a retry runs the function again. */
    public Mono<FunctionResponse> invoke(FunctionRequest request) {
        return calls.future(blocking -> async(blocking).invoke(request));
    }

    /** Starts the function without waiting for it (see {@link FunctionInvoker#trigger}). */
    public Mono<Void> trigger(FunctionRequest request) {
        return calls.future(blocking -> async(blocking).trigger(request)).then();
    }

    public <T> T unwrap(Class<T> nativeType) {
        return invoker.unwrap(nativeType);
    }

    @Override
    public String toString() {
        return "ReactiveFunctionInvoker[" + invoker + "]";
    }
}
