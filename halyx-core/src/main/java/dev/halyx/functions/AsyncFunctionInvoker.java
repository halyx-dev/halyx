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
package dev.halyx.functions;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * The asynchronous view of a {@link FunctionInvoker}, from {@link FunctionInvoker#async()}: the same invocations and
 * failures, as {@code CompletableFuture}s. Where the provider's driver has a non-blocking path, no thread waits for
 * the network. Limit and capability checks, interceptors and error translation are those of the blocking calls.
 */
public interface AsyncFunctionInvoker {

    String provider();

    String function();

    CompletableFuture<FunctionResponse> invoke(FunctionRequest request);

    /** As {@link FunctionInvoker#trigger}: completes once the cloud has accepted the invocation. */
    CompletableFuture<Void> trigger(FunctionRequest request);

    /** The blocking invoker this is a view of. */
    FunctionInvoker blocking();

    /** A view that runs each of {@code invoker}'s blocking calls on {@code executor}: for invokers without one. */
    static AsyncFunctionInvoker onExecutor(FunctionInvoker invoker, Executor executor) {
        Objects.requireNonNull(invoker, "invoker");
        Objects.requireNonNull(executor, "executor");
        return new AsyncFunctionInvoker() {
            @Override
            public String provider() {
                return invoker.provider();
            }

            @Override
            public String function() {
                return invoker.function();
            }

            @Override
            public CompletableFuture<FunctionResponse> invoke(FunctionRequest request) {
                return CompletableFuture.supplyAsync(() -> invoker.invoke(request), executor);
            }

            @Override
            public CompletableFuture<Void> trigger(FunctionRequest request) {
                return CompletableFuture.runAsync(() -> invoker.trigger(request), executor);
            }

            @Override
            public FunctionInvoker blocking() {
                return invoker;
            }
        };
    }
}
