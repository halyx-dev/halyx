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
package dev.halyx.redis;

import dev.halyx.cache.spi.CacheDriver;
import dev.halyx.error.UnavailableException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LazyCacheDriverTest {

    @Test
    void callersShareOneConnectWhileTheServerIsDownAndFailFastUntilTheNextTry() throws Exception {
        AtomicInteger connects = new AtomicInteger();
        AtomicBoolean up = new AtomicBoolean();
        LazyCacheDriver driver = new LazyCacheDriver("test", () -> {
            connects.incrementAndGet();
            sleep(Duration.ofMillis(300)); // a connect timing out
            if (!up.get()) {
                throw new UnavailableException("connection refused");
            }
            return connected();
        });

        // 20 callers at once, as requests reaching a fail-open cache: one connect, not one each in turn.
        long start = System.nanoTime();
        List<Thread> callers = new ArrayList<>();
        AtomicInteger failed = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            callers.add(Thread.ofVirtual().start(() -> {
                try {
                    driver.get("k");
                } catch (UnavailableException e) {
                    failed.incrementAndGet();
                }
            }));
        }
        for (Thread caller : callers) {
            caller.join();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        assertThat(failed).hasValue(20);
        assertThat(connects).hasValue(1);

        // Straight after, a call reuses the failure instead of connecting again.
        assertThatThrownBy(() -> driver.get("k")).isInstanceOf(UnavailableException.class);
        assertThat(connects).hasValue(1);

        // Once the retry delay has passed and the server is back, the next call connects.
        up.set(true);
        sleep(LazyCacheDriver.RETRY_AFTER.plusMillis(100));
        assertThat(driver.get("k")).isEmpty();
        assertThat(driver.get("k")).isEmpty();
        assertThat(connects).hasValue(2);
    }

    private static CacheDriver connected() {
        return (CacheDriver) Proxy.newProxyInstance(CacheDriver.class.getClassLoader(), new Class<?>[] {CacheDriver.class},
                (proxy, method, args) -> method.getName().equals("get") ? Optional.empty() : null);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
