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
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.interceptor.Invocation;
import dev.halyx.interceptor.OperationContext;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves a driver's non-blocking path: its asynchronous calls complete on the SDK's own threads, not on Halyx's
 * blocking fallback, nor on the caller's thread.
 *
 * <p>Each call is observed from the moment it starts. While a call is in flight, the thread that completes it is
 * recorded. Against a local emulator, a response can arrive before the call has even returned its future, so that
 * thread can't be seen. In that case the call counts as non-blocking only if the caller's thread never waited during
 * it: a driver that blocks the caller always parks it for the I/O. The fallback is detected by the executor itself,
 * with {@link #executor()} passed to the service's {@code async(Executor)} view, so its check doesn't depend on
 * timing at all.
 */
public final class CompletingThreads implements Interceptor {

    /** Recorded for a call that completed, on another thread, before it returned its future. */
    public static final String COMPLETED_BEFORE_RETURNING = "<completed before returning, caller never waited>";

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private final List<String> names = new CopyOnWriteArrayList<>();
    private final AtomicInteger fallbacks = new AtomicInteger();
    private final Executor executor = task -> {
        fallbacks.incrementAndGet();
        InterceptorChain.blocking().execute(task);
    };

    @Override
    public <T> T intercept(OperationContext context, Invocation<T> next) {
        return next.proceed();
    }

    @Override
    public <T> CompletableFuture<T> interceptAsync(OperationContext context, AsyncInvocation<T> next, Executor blocking) {
        return observe(next::proceed);
    }

    /** Observes {@code call}, for calls made without this interceptor: pass the call itself, not its result. */
    public <T> CompletableFuture<T> track(Supplier<CompletableFuture<T>> call) {
        return observe(call);
    }

    /** An executor for the service's {@code async(Executor)} view, which counts every task Halyx's fallback runs. */
    public Executor executor() {
        return executor;
    }

    public List<String> names() {
        return List.copyOf(names);
    }

    /**
     * Asserts that {@code calls} calls completed, none on Halyx' blocking fallback nor on {@code caller}, and that
     * {@link #executor()}, where it was used, ran no fallback work.
     */
    public void assertNonBlocking(int calls, Thread caller) {
        assertThat(names).hasSize(calls).allSatisfy(name -> assertThat(name)
                .doesNotStartWith("halyx-async-").isNotEqualTo(caller.getName()));
        assertThat(fallbacks).as("calls run on Halyx's blocking fallback").hasValue(0);
    }

    private <T> CompletableFuture<T> observe(Supplier<CompletableFuture<T>> call) {
        long waited = waitedCount();
        CompletableFuture<T> future = call.get();
        if (!future.isDone()) {
            return future.whenComplete((value, error) -> names.add(Thread.currentThread().getName()));
        }
        // Already complete: the completing thread can't be seen. A driver that blocked the caller parked it.
        names.add(waitedCount() > waited ? Thread.currentThread().getName() : COMPLETED_BEFORE_RETURNING);
        return future;
    }

    private static long waitedCount() {
        return THREADS.getThreadInfo(Thread.currentThread().threadId()).getWaitedCount();
    }
}
