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

import dev.halyx.error.NotFoundException;
import dev.halyx.error.ThrottledException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncInterceptorChainTest {

    private static final OperationContext IDEMPOTENT = new OperationContext("storage", "get", "test", "b/k", true);
    private static final OperationContext NOT_IDEMPOTENT = new OperationContext("storage", "put", "test", "b/k", false);

    private static InterceptorChain retrying(int attempts) {
        return new InterceptorChain(List.of(
                new RetryInterceptor(new RetryPolicy(attempts, Duration.ofMillis(1), Duration.ofMillis(5), 2.0))));
    }

    @Test
    void retriesRetryableFailuresWithoutHoldingAThread() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        CompletableFuture<String> result = retrying(3).executeAsync(IDEMPOTENT, () -> {
            threads.add(Thread.currentThread());
            return calls.incrementAndGet() < 3
                    ? CompletableFuture.failedFuture(new ThrottledException("slow down"))
                    : CompletableFuture.completedFuture("ok");
        });

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        // The first attempt starts on the caller's thread; retries start from the timer, not a sleeping thread.
        assertThat(threads.getFirst()).isSameAs(Thread.currentThread());
    }

    @Test
    void stopsAtMaxAttemptsAndKeepsTheLastFailure() {
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<String> result = retrying(2).executeAsync(IDEMPOTENT, () -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new ThrottledException("slow down"));
        });

        assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ThrottledException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void neitherRetriesNonRetryableFailuresNorNonIdempotentCalls() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> retrying(3).executeAsync(IDEMPOTENT, () -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new NotFoundException("gone"));
        }).get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> retrying(3).executeAsync(NOT_IDEMPOTENT, () -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new ThrottledException("slow down"));
        }).get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ThrottledException.class);

        assertThat(calls).hasValue(2);
    }

    @Test
    void cancellingStopsFurtherRetries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        InterceptorChain chain = new InterceptorChain(List.of(
                new RetryInterceptor(new RetryPolicy(10, Duration.ofMillis(200), Duration.ofMillis(200), 1.0))));
        CompletableFuture<String> result = chain.executeAsync(IDEMPOTENT, () -> {
            calls.incrementAndGet();
            return CompletableFuture.failedFuture(new ThrottledException("slow down"));
        });

        result.cancel(true);
        Thread.sleep(600);

        assertThat(calls.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void aBlockingInterceptorStillWrapsEveryAsyncCall() throws Exception {
        List<String> seen = new ArrayList<>();
        Interceptor blocking = new Interceptor() {
            @Override
            public <T> T intercept(OperationContext context, Invocation<T> next) {
                seen.add("before " + context.operation());
                T result = next.proceed();
                seen.add("after " + result);
                return result;
            }
        };
        InterceptorChain chain = new InterceptorChain(List.of(blocking));

        assertThat(chain.executeAsync(IDEMPOTENT, () -> CompletableFuture.completedFuture("v")).get(5, TimeUnit.SECONDS))
                .isEqualTo("v");
        assertThat(seen).containsExactly("before get", "after v");
        assertThatThrownBy(() -> chain.executeAsync(IDEMPOTENT,
                () -> CompletableFuture.<String>failedFuture(new NotFoundException("gone"))).get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(NotFoundException.class);
    }

    @Test
    void aFailureThrownInsteadOfReturnedFailsTheFuture() {
        CompletableFuture<String> result = InterceptorChain.empty().executeAsync(IDEMPOTENT, () -> {
            throw new NotFoundException("thrown");
        });

        assertThat(result).isCompletedExceptionally();
        assertThatThrownBy(result::join).hasCauseInstanceOf(NotFoundException.class);
    }

    @Test
    void callbacksRunOffTheSdksNetworkThreadSoTheyMayBlock() throws Exception {
        // One network thread, as Lettuce has per connection: it completes calls, and reads every reply.
        ExecutorService network = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("sdk-io").factory());
        try {
            CountDownLatch replied = new CountDownLatch(1);
            CompletableFuture<String> call = InterceptorChain.empty().executeAsync(IDEMPOTENT,
                    () -> CompletableFuture.supplyAsync(() -> {
                        await(replied);
                        return "first";
                    }, network));

            // The callback blocks on a second call, whose reply needs the network thread.
            CompletableFuture<String> chained = call.thenApply(first -> {
                try {
                    return first + "+" + network.submit(() -> "second").get(5, TimeUnit.SECONDS)
                            + " on " + Thread.currentThread().getName();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            replied.countDown();

            assertThat(chained.get(10, TimeUnit.SECONDS)).startsWith("first+second on halyx-complete-");
        } finally {
            network.shutdownNow();
        }
    }

    @Test
    void cancellingTheReturnedFutureCancelsTheCall() {
        CompletableFuture<String> pending = new CompletableFuture<>();
        CompletableFuture<String> returned = InterceptorChain.empty().executeAsync(IDEMPOTENT, () -> pending);

        returned.cancel(false);

        assertThat(pending).isCancelled();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
