package org.kamranzafar.cirrus.interceptor;

import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.error.NotFoundException;
import org.kamranzafar.cirrus.error.ThrottledException;

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
    void waitsAtLeastTheRetryAfterDelay() {
        var patient = new InterceptorChain(List.of(
                new RetryInterceptor(new RetryPolicy(2, Duration.ofMillis(1), Duration.ofSeconds(1), 2.0))));
        AtomicInteger calls = new AtomicInteger();
        long started = System.nanoTime();

        String result = patient.execute(context(true), () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ThrottledException("slow down", null, "test", "429", Duration.ofMillis(150));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(150));
    }

    @Test
    void retryAfterBeyondMaxBackoffFailsFast() {
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> chain.execute(context(true), () -> {
            calls.incrementAndGet();
            throw new ThrottledException("slow down", null, "test", "429", Duration.ofMinutes(1));
        })).isInstanceOf(ThrottledException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void parsesRetryAfterSecondsAndDates() {
        assertThat(ThrottledException.parseRetryAfter("120")).isEqualTo(Duration.ofSeconds(120));
        assertThat(ThrottledException.parseRetryAfter(" 3 ")).isEqualTo(Duration.ofSeconds(3));
        String inTenSeconds = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
                .format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(10));
        assertThat(ThrottledException.parseRetryAfter(inTenSeconds)).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(10));
        assertThat(ThrottledException.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT")).isEqualTo(Duration.ZERO);
        assertThat(ThrottledException.parseRetryAfter("soon")).isNull();
        assertThat(ThrottledException.parseRetryAfter(null)).isNull();
        assertThat(new ThrottledException("x").retryAfter()).isEmpty();
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
