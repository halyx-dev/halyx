package org.kamranzafar.stratum.interceptor;

import org.kamranzafar.stratum.error.CloudException;

import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retries idempotent operations that fail with a {@linkplain CloudException#isRetryable() retryable}
 * error.
 *
 * <p>Not installed by default: the cloud SDKs already retry transient failures internally, and
 * stacking two retry layers multiplies attempts. Add this one when you want uniform, provider-neutral
 * retry behaviour on top of (or instead of) SDK retries.
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
                sleep(policy.backoffBound(attempt).toMillis(), e);
                attempt++;
            }
        }
    }

    private static void sleep(long boundMillis, CloudException cause) {
        try {
            Thread.sleep(boundMillis <= 0 ? 0 : ThreadLocalRandom.current().nextLong(boundMillis + 1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            cause.addSuppressed(ie);
            throw cause;
        }
    }
}
