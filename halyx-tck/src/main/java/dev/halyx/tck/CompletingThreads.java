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
package dev.halyx.tck;

import dev.halyx.interceptor.AsyncInvocation;
import dev.halyx.interceptor.Interceptor;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records which thread completes each asynchronous call, attached while the request is still in flight, to prove a
 * driver's non-blocking path: its calls complete on the SDK's own threads, not on a virtual thread running the
 * blocking call, nor on the caller's.
 */
public final class CompletingThreads implements Interceptor {

    private final List<String> names = new CopyOnWriteArrayList<>();

    @Override
    public <T> T intercept(OperationContext context, Invocation<T> next) {
        return next.proceed();
    }

    @Override
    public <T> CompletableFuture<T> interceptAsync(OperationContext context, AsyncInvocation<T> next, Executor blocking) {
        return next.proceed().whenComplete((value, error) -> names.add(Thread.currentThread().getName()));
    }

    /** Records the thread that completes {@code call}, for calls made without this interceptor. */
    public <T> CompletableFuture<T> track(CompletableFuture<T> call) {
        return call.whenComplete((value, error) -> names.add(Thread.currentThread().getName()));
    }

    public List<String> names() {
        return List.copyOf(names);
    }

    /** Asserts that {@code calls} calls completed, none on Halyx' blocking fallback nor on {@code caller}. */
    public void assertNonBlocking(int calls, Thread caller) {
        assertThat(names).hasSize(calls).allSatisfy(name -> assertThat(name)
                .doesNotStartWith("halyx-async-").isNotEqualTo(caller.getName()));
    }
}
