package org.kamranzafar.stratum.interceptor;

import org.junit.jupiter.api.Test;
import org.kamranzafar.stratum.error.NotFoundException;
import org.kamranzafar.stratum.error.ThrottledException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryInterceptorTest {

    private final InterceptorChain chain = new InterceptorChain(List.of(
            new RetryInterceptor(new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5), 2.0))));

    private static OperationContext context(boolean idempotent) {
        return new OperationContext("storage", "get", "test", "b/k", idempotent);
    }

    @Test
    void retriesRetryableFailuresUntilSuccess() {
        AtomicInteger calls = new AtomicInteger();
        String result = chain.execute(context(true), () -> {
            if (calls.incrementAndGet() < 3) {
                throw new ThrottledException("slow down");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> chain.execute(context(true), () -> {
            calls.incrementAndGet();
            throw new ThrottledException("slow down");
        })).isInstanceOf(ThrottledException.class);
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotRetryNonRetryableErrors() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> chain.execute(context(true), () -> {
            calls.incrementAndGet();
            throw new NotFoundException("missing");
        })).isInstanceOf(NotFoundException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRetryNonIdempotentOperations() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> chain.execute(context(false), () -> {
            calls.incrementAndGet();
            throw new ThrottledException("slow down");
        })).isInstanceOf(ThrottledException.class);
        assertThat(calls).hasValue(1);
    }
}
