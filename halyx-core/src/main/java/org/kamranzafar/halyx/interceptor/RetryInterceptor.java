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
package org.kamranzafar.halyx.interceptor;

import org.kamranzafar.halyx.error.CloudException;
import org.kamranzafar.halyx.error.ThrottledException;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retries idempotent operations that fail with a {@linkplain CloudException#isRetryable() retryable}
 * error.
 *
 * <p>Not installed by default: the cloud SDKs already retry transient failures internally, and
 * stacking two retry layers multiplies attempts. Add this one when you want uniform, provider-neutral
 * retry behaviour on top of (or instead of) SDK retries.
 *
 * <p>When a {@link ThrottledException} carries a {@code Retry-After} delay, the retry waits at least that
 * long. If the provider asks for longer than {@link RetryPolicy#maxBackoff()}, the error is rethrown
 * straight away instead of retrying early or blocking the caller for that long.
 */
public final class RetryInterceptor implements Interceptor {

    private final RetryPolicy policy;

    public RetryInterceptor(RetryPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public <T> T intercept(OperationContext context, Invocation<T> next) {
        int attempt = 1;
        while (true) {
            try {
                return next.proceed();
            } catch (CloudException e) {
                if (!context.idempotent() || !e.isRetryable() || attempt >= policy.maxAttempts()) {
                    throw e;
                }
                long backoff = jittered(policy.backoffBound(attempt).toMillis());
                if (e instanceof ThrottledException throttled && throttled.retryAfter().isPresent()) {
                    long asked = throttled.retryAfter().get().toMillis();
                    if (asked > policy.maxBackoff().toMillis()) {
                        throw e;
                    }
                    backoff = Math.max(backoff, asked);
                }
                sleep(backoff, e);
                attempt++;
            }
        }
    }

    /** Full jitter: a random delay up to the bound, so throttled clients don't retry in lockstep. */
    private static long jittered(long boundMillis) {
        return boundMillis <= 0 ? 0 : ThreadLocalRandom.current().nextLong(boundMillis + 1);
    }

    private static void sleep(long millis, CloudException cause) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cause.addSuppressed(ie);
            throw cause;
        }
    }
}
