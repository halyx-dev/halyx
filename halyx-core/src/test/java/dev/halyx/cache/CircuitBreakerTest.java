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
package dev.halyx.cache;

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
