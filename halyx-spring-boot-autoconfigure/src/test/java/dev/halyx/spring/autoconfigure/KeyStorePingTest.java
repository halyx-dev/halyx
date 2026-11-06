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
package dev.halyx.spring.autoconfigure;

import dev.halyx.error.ThrottledException;
import dev.halyx.keys.KeyStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyStorePingTest {

    private final AtomicInteger pings = new AtomicInteger();
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicLong now = new AtomicLong();
    private final KeyStorePing ping = new KeyStorePing(store(() -> { }), Duration.ofSeconds(30), now::get);

    @Test
    void anAnswerStandsForItsTtl() {
        ping.check();
        now.addAndGet(Duration.ofSeconds(29).toNanos());
        ping.check();
        assertThat(pings).hasValue(1);

        now.addAndGet(Duration.ofSeconds(1).toNanos());
        ping.check();
        assertThat(pings).hasValue(2);
    }

    @Test
    void aFailureStandsTooAndRecoveryShowsAfterIt() {
        failure.set(new ThrottledException("Rate exceeded", null, "aws", "ThrottlingException"));

        assertThatThrownBy(ping::check).isInstanceOf(ThrottledException.class);
        assertThatThrownBy(ping::check).as("no new call to a throttling service").isInstanceOf(ThrottledException.class);
        assertThat(pings).hasValue(1);

        failure.set(null);
        now.addAndGet(Duration.ofSeconds(30).toNanos());
        ping.check();
        assertThat(pings).hasValue(2);
    }

    @Test
    void concurrentProbesShareOnePing() throws Exception {
        CountDownLatch inPing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        KeyStorePing slow = new KeyStorePing(store(() -> {
            inPing.countDown();
            await(release);
        }), Duration.ofSeconds(30), now::get);
        try (ExecutorService probes = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> all = new ArrayList<>();
            all.add(probes.submit(slow::check));
            assertThat(inPing.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 7; i++) {
                all.add(probes.submit(slow::check));
            }
            release.countDown();
            for (Future<?> probe : all) {
                probe.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(pings).hasValue(1);
    }

    private KeyStore store(Runnable onPing) {
        return (KeyStore) Proxy.newProxyInstance(KeyStore.class.getClassLoader(), new Class<?>[] {KeyStore.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("ping")) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    pings.incrementAndGet();
                    onPing.run();
                    RuntimeException e = failure.get();
                    if (e != null) {
                        throw e;
                    }
                    return null;
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
