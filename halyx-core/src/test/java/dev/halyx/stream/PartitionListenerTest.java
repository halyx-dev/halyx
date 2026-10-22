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
package dev.halyx.stream;

import dev.halyx.Halyx;
import dev.halyx.capability.Capabilities;
import dev.halyx.config.ProviderConfig;
import dev.halyx.error.CloudException;
import dev.halyx.error.UnknownCloudException;
import dev.halyx.interceptor.InterceptorChain;
import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.DriverRecord;
import dev.halyx.stream.spi.Partition;
import dev.halyx.stream.spi.RebalanceListener;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionListenerTest {

    /** One partition with three records, whose asynchronous commits are lost, as when the broker rejects them. */
    private static final class LossyCommitsDriver implements ConsumerDriver {
        final Partition partition = new Partition("ledger", 0);
        final Map<Partition, Long> committed = new ConcurrentHashMap<>();
        final Map<Partition, Long> lastAsyncCommit = new ConcurrentHashMap<>();
        private RebalanceListener listener;
        private boolean assigned;
        private boolean delivered;

        @Override public String provider() { return "test"; }
        @Override public String topic() { return partition.topic(); }
        @Override public String group() { return "billing"; }
        @Override public Capabilities capabilities() { return Capabilities.none(); }
        @Override public void subscribe(RebalanceListener listener) { this.listener = listener; }
        @Override public Set<Partition> assignment() { return assigned ? Set.of(partition) : Set.of(); }
        @Override public void seek(Partition partition, long offset) { }
        @Override public void pause(Collection<Partition> partitions) { }
        @Override public void resume(Collection<Partition> partitions) { }
        @Override public void commit(Map<Partition, Long> nextOffsets) { committed.putAll(nextOffsets); }
        @Override public void commitAsync(Map<Partition, Long> nextOffsets) { lastAsyncCommit.putAll(nextOffsets); }
        @Override public void wakeup() { }
        @Override public CloudException translate(Throwable error) { return new UnknownCloudException(error.getMessage()); }
        @Override public <T> T unwrap(Class<T> nativeType) { throw new IllegalArgumentException(); }
        @Override public void close() { }

        @Override
        public List<DriverRecord> poll(Duration wait) {
            if (!assigned) {
                assigned = true;
                listener.onAssigned(List.of(partition));
            }
            if (!delivered) {
                delivered = true;
                return List.of(record(0), record(1), record(2));
            }
            try {
                Thread.sleep(Math.min(wait.toMillis(), 20));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }

        private DriverRecord record(long offset) {
            return new DriverRecord(partition, offset, "account", ("entry-" + offset).getBytes(StandardCharsets.UTF_8),
                    Map.of(), Instant.now());
        }
    }

    @Test
    void stoppingCommitsWhatWasHandledEvenIfAPeriodicCommitWasLost() {
        LossyCommitsDriver driver = new LossyCommitsDriver();
        StreamConsumer consumer = new DefaultStreamConsumer(driver,
                StreamConsumerConfig.of(StreamConfig.builder("test:9092").build(), "ledger", "billing"),
                InterceptorChain.empty());
        AtomicInteger handled = new AtomicInteger();
        StreamListener listener = consumer.listen(record -> handled.incrementAndGet(),
                ListenerOptions.DEFAULTS.withPollWait(Duration.ofMillis(20)).withCommitInterval(Duration.ofMillis(10)));

        await("all three records handled, and an asynchronous commit sent for them",
                () -> handled.get() == 3 && Long.valueOf(3).equals(driver.lastAsyncCommit.get(driver.partition)));
        listener.close();

        assertThat(driver.committed).containsEntry(driver.partition, 3L);
    }

    @Test
    void aHandlerCanCloseItsOwnListenerWithoutWaitingForItself() {
        try (Halyx halyx = Halyx.builder().register("local", ProviderConfig.of("memory")).build()) {
            StreamConfig cluster = StreamConfig.builder("memory:9092").build();
            StreamConsumer consumer = halyx.provider("local").streamConsumer(
                    StreamConsumerConfig.builder(cluster, "orders", "shipping").startPosition(StartPosition.EARLIEST).build());
            AtomicReference<StreamListener> self = new AtomicReference<>();
            AtomicLong closeTookMillis = new AtomicLong(-1);
            self.set(consumer.listen(record -> {
                long start = System.nanoTime();
                self.get().close();
                closeTookMillis.set(Duration.ofNanos(System.nanoTime() - start).toMillis());
            }, ListenerOptions.DEFAULTS.withPollWait(Duration.ofMillis(50)).withShutdownTimeout(Duration.ofSeconds(10))));

            halyx.provider("local").streamProducer(StreamProducerConfig.of(cluster, "orders")).send("order-1", "placed");

            await("the handler's close() returned", () -> closeTookMillis.get() >= 0);
            assertThat(closeTookMillis.get()).isLessThan(2000);
            await("the listener stopped", () -> !self.get().isRunning());
        }
    }

    private static void await(String what, BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime() - deadline).as(what).isNegative();
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }
}
