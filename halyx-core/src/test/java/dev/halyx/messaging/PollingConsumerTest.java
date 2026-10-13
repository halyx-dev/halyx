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
package dev.halyx.messaging;

import org.junit.jupiter.api.Test;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.messaging.spi.DriverMessage;
import dev.halyx.messaging.spi.SubscriptionDriver;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PollingConsumerTest {

    /** Hands out everything at once, the way an SQS FIFO receive returns a whole message group. */
    private static final class BatchDriver implements SubscriptionDriver {
        final ConcurrentLinkedQueue<DriverMessage> pending = new ConcurrentLinkedQueue<>();
        final List<String> acked = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger extensions = new AtomicInteger();
        final Duration lease;

        BatchDriver(Duration lease) {
            this.lease = lease;
        }

        void add(String body, String key) {
            pending.add(new DriverMessage(body, body, body.getBytes(StandardCharsets.UTF_8), Map.of(), key, null, 1));
        }

        @Override public String provider() { return "test"; }
        @Override public String name() { return "batch"; }
        @Override public Capabilities capabilities() { return Capabilities.none(); }

        @Override
        public List<DriverMessage> receive(int max, Duration wait) {
            List<DriverMessage> out = new ArrayList<>();
            DriverMessage m;
            while (out.size() < max && (m = pending.poll()) != null) {
                out.add(m);
            }
            if (out.isEmpty()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return out;
        }

        @Override public int maxReceiveBatch() { return 100; }
        int ackBatch = 1;
        final AtomicInteger ackRequests = new AtomicInteger();

        @Override public void ack(DriverMessage message) { ackRequests.incrementAndGet(); acked.add(message.id()); }
        @Override public void ackAll(List<DriverMessage> messages) { ackRequests.incrementAndGet(); messages.forEach(m -> acked.add(m.id())); }
        @Override public int maxAckBatch() { return ackBatch; }
        @Override public void nack(DriverMessage message, Duration delay) { pending.add(message); }
        @Override public void extendLease(DriverMessage message, Duration lease) { extensions.incrementAndGet(); }
        @Override public Duration defaultLease() { return lease; }
        @Override public CloudException translate(Throwable error) { return new UnknownCloudException(error.getMessage()); }
        @Override public <T> T unwrap(Class<T> nativeType) { throw new IllegalArgumentException(); }
    }

    private static Subscription subscription(BatchDriver driver) {
        return new DefaultSubscription(driver, SubscriptionConfig.of("batch"), InterceptorChain.empty());
    }

    @Test
    void messagesWithTheSameKeyRunOneAtATimeInOrderEvenWithinABatch() throws InterruptedException {
        var driver = new BatchDriver(Duration.ofMinutes(1));
        for (int i = 0; i < 10; i++) {
            driver.add("a" + i, "a");
            driver.add("b" + i, "b");
            driver.add("free" + i, null);
        }
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger runningA = new AtomicInteger();
        AtomicInteger peakA = new AtomicInteger();
        CountDownLatch all = new CountDownLatch(30);

        try (MessageConsumer ignored = subscription(driver).listen(message -> {
            boolean a = message.orderingKey().filter("a"::equals).isPresent();
            if (a) {
                peakA.accumulateAndGet(runningA.incrementAndGet(), Math::max);
            }
            Thread.sleep(5);
            order.add(message.bodyAsString());
            if (a) {
                runningA.decrementAndGet();
            }
            all.countDown();
        }, ConsumerOptions.DEFAULTS.withConcurrency(30).withPollWait(Duration.ofMillis(50)))) {
            assertThat(all.await(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(peakA.get()).isEqualTo(1);
        assertThat(order.stream().filter(s -> s.startsWith("a")).toList())
                .containsExactly("a0", "a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9");
        assertThat(order.stream().filter(s -> s.startsWith("b")).toList())
                .containsExactly("b0", "b1", "b2", "b3", "b4", "b5", "b6", "b7", "b8", "b9");
    }

    @Test
    void leasesAreExtendedWhileAHandlerRuns() throws InterruptedException {
        var driver = new BatchDriver(Duration.ofMillis(200));
        driver.add("slow", null);
        CountDownLatch done = new CountDownLatch(1);

        try (MessageConsumer consumer = subscription(driver).listen(message -> {
            Thread.sleep(700);
            done.countDown();
        }, ConsumerOptions.DEFAULTS.withPollWait(Duration.ofMillis(50)))) {
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            assertThat(consumer.statistics().acked()).isEqualTo(1);
        }
        assertThat(driver.extensions.get()).isGreaterThanOrEqualTo(3);
        assertThat(driver.acked).containsExactly("slow");
    }

    @Test
    void backoffDoublesUpToTheMaximum() {
        var options = ConsumerOptions.DEFAULTS.withBackoff(Duration.ofSeconds(1), Duration.ofSeconds(10));
        assertThat(options.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(options.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(options.backoff(10)).isEqualTo(Duration.ofSeconds(10));
        assertThat(options.backoff(0)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void listenerAcksAreSentInBatches() throws InterruptedException {
        var driver = new BatchDriver(Duration.ofMinutes(1));
        driver.ackBatch = 10;
        for (int i = 0; i < 25; i++) {
            driver.add("m" + i, null);
        }
        CountDownLatch all = new CountDownLatch(25);

        try (MessageConsumer ignored = subscription(driver).listen(message -> all.countDown(),
                ConsumerOptions.DEFAULTS.withConcurrency(25).withPollWait(Duration.ofMillis(50)))) {
            assertThat(all.await(5, TimeUnit.SECONDS)).isTrue();
        }

        // close() flushes the last partial batch.
        assertThat(driver.acked).hasSize(25);
        assertThat(driver.ackRequests.get()).isLessThanOrEqualTo(5);
    }

    @Test
    void lonelyAcksAreFlushedAfterTheLinger() throws InterruptedException {
        var driver = new BatchDriver(Duration.ofMinutes(1));
        driver.ackBatch = 10;
        driver.add("solo", null);
        CountDownLatch handled = new CountDownLatch(1);

        try (MessageConsumer ignored = subscription(driver).listen(message -> handled.countDown(),
                ConsumerOptions.DEFAULTS.withPollWait(Duration.ofMillis(50)))) {
            assertThat(handled.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(DefaultSubscription.ACK_LINGER.toMillis() * 3);
            assertThat(driver.acked).containsExactly("solo");
        }
    }
}
