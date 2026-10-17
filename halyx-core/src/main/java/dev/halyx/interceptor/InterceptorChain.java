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
package dev.halyx.interceptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Runs interceptors in registration order (the first registered is outermost), ending with the
 * terminal provider call.
 */
public final class InterceptorChain {

    private static final InterceptorChain EMPTY = new InterceptorChain(List.of());

    /** Runs blocking work for asynchronous calls: a driver without a non-blocking path, or a blocking interceptor. */
    private static final Executor BLOCKING = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("halyx-async-", 0).factory());

    private final List<Interceptor> interceptors;

    public InterceptorChain(List<Interceptor> interceptors) {
        this.interceptors = List.copyOf(interceptors);
    }

    public static InterceptorChain empty() {
        return EMPTY;
    }

    public <T> T execute(OperationContext context, Invocation<T> terminal) {
        return proceed(0, context, terminal);
    }

    /**
     * Runs an asynchronous operation through the interceptors; the terminal call returns at once with a future. A
     * failure thrown instead of returned is turned into a failed future, so callers see one kind of failure.
     */
    public <T> CompletableFuture<T> executeAsync(OperationContext context, AsyncInvocation<T> terminal) {
        return executeAsync(context, terminal, BLOCKING);
    }

    /** The same, running blocking interceptors on {@code blocking} instead of the shared virtual threads. */
    public <T> CompletableFuture<T> executeAsync(OperationContext context, AsyncInvocation<T> terminal, Executor blocking) {
        return proceedAsync(0, context, terminal, blocking);
    }

    /** Where asynchronous calls run blocking work: virtual threads, shared by every chain. */
    public static Executor blocking() {
        return BLOCKING;
    }

    private <T> CompletableFuture<T> proceedAsync(int index, OperationContext context, AsyncInvocation<T> terminal,
                                                  Executor blocking) {
        try {
            if (index == interceptors.size()) {
                return terminal.proceed();
            }
            return interceptors.get(index).interceptAsync(context,
                    () -> proceedAsync(index + 1, context, terminal, blocking), blocking);
        } catch (RuntimeException | Error e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private <T> T proceed(int index, OperationContext context, Invocation<T> terminal) {
        if (index == interceptors.size()) {
            return terminal.proceed();
        }
        return interceptors.get(index).intercept(context, () -> proceed(index + 1, context, terminal));
    }
}
