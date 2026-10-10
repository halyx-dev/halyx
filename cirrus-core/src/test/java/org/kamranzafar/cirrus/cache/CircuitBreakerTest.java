package org.kamranzafar.cirrus.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    @Test
    void opensAfterThresholdAndProbesOnceAfterCooldown() throws Exception {
        CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofMillis(200));
        breaker.onFailure();
        breaker.onFailure();
        assertThat(breaker.allowsCall()).isTrue();

        breaker.onFailure();
        assertThat(breaker.isOpen()).isTrue();
        assertThat(breaker.allowsCall()).isFalse();

        Thread.sleep(250);
        assertThat(breaker.allowsCall()).as("one probe").isTrue();
        assertThat(breaker.allowsCall()).as("only one probe").isFalse();

        breaker.onSuccess();
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allowsCall()).isTrue();
    }
}
