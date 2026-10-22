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

import dev.halyx.stream.spi.ConsumerDriver;
import dev.halyx.stream.spi.DriverRecord;
import dev.halyx.stream.spi.Partition;
import dev.halyx.stream.spi.RebalanceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads on one virtual thread and handles each owned partition in order on its own virtual thread, so
 * partitions run in parallel. The reader never stops polling, which keeps this member in its group however long
 * a handler runs: a partition whose handler is retrying, or whose buffer is full, is paused instead. A failed
 * record is retried with exponential backoff, holding back the rest of its partition; after
 * {@code maxAttempts} it goes to the dead-letter producer. Handled offsets are committed every
 * {@code commitInterval}, and before partitions are handed to another member or the listener stops. A stopped
 * listener leaves its member reading from the first record it didn't handle, so a later listener on the same
 * member carries on from there.
 */
final class PartitionListener implements StreamListener {

    private static final Logger LOG = LoggerFactory.getLogger(PartitionListener.class);
    private static final Duration MAX_POLL_BACKOFF = Duration.ofSeconds(30);
    static final String DEAD_LETTER_TOPIC = DefaultStreamProducer.DEAD_LETTER_PREFIX + "topic";
    static final String DEAD_LETTER_PARTITION = DefaultStreamProducer.DEAD_LETTER_PREFIX + "partition";
    static final String DEAD_LETTER_OFFSET = DefaultStreamProducer.DEAD_LETTER_PREFIX + "offset";
    static final String DEAD_LETTER_ERROR = DefaultStreamProducer.DEAD_LETTER_PREFIX + "error";

    private final DefaultStreamConsumer consumer;
    private final ConsumerDriver driver;
    private final RecordListener listener;
    private final ListenerOptions options;
    private final Map<Partition, PartitionWork> partitions = new ConcurrentHashMap<>();
    private final Rebalance rebalance = new Rebalance();
    private final ExecutorService handlers = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("halyx-stream-handle-", 0).factory());
    private final Thread poller;
    private volatile boolean running = true;
    /** When handled offsets were last committed; used on the polling thread only. */
    private long lastCommit = System.nanoTime();
    /** The poller has finished; guarded by this listener's lock. */
    private boolean exited;
    /** The poller closes the driver as it finishes, since only that thread may use it; guarded likewise. */
    private boolean closeDriverOnExit;

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong handled = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong pollErrors = new AtomicLong();

    PartitionListener(DefaultStreamConsumer consumer, RecordListener listener, ListenerOptions options) {
        this.consumer = consumer;
        this.driver = consumer.driver();
        this.listener = listener;
        this.options = options;
        this.poller = Thread.ofVirtual().name("halyx-stream-poll-" + consumer.topic() + "-" + consumer.group())
                .start(this::run);
    }

    private void run() {
        try {
            driver.subscribe(rebalance);
            // A member that ran a listener before still owns its partitions, and re-subscribing doesn't report
            // them again.
            Set<Partition> owned = driver.assignment();
            if (!owned.isEmpty()) {
                rebalance.onAssigned(owned);
            }
            int consecutiveErrors = 0;
            while (running) {
                List<DriverRecord> records;
                try {
                    records = consumer.execute("poll", true, () -> driver.poll(options.pollWait()));
                    consecutiveErrors = 0;
                } catch (RuntimeException e) {
                    if (!running) {
                        break;
                    }
                    pollErrors.incrementAndGet();
                    consecutiveErrors++;
                    long backoff = Math.min(MAX_POLL_BACKOFF.toMillis(), 200L << Math.min(consecutiveErrors, 8));
                    LOG.warn("Reading {} failed ({} in a row); retrying in {} ms", consumer, consecutiveErrors, backoff, e);
                    sleep(ThreadLocalRandom.current().nextLong(backoff / 2, backoff + 1));
                    continue;
                }
                dispatch(records);
                pauseAndResume();
                commitIfDue();
            }
        } catch (RuntimeException e) {
            LOG.error("Listener on {} stopped", consumer, e);
        } finally {
            try {
                shutdown();
            } finally {
                exit();
            }
        }
    }

    private void exit() {
        boolean closeDriver;
        synchronized (this) {
            exited = true;
            closeDriver = closeDriverOnExit;
        }
        if (closeDriver) {
            try {
                driver.close();
            } catch (RuntimeException e) {
                LOG.warn("Could not close {} cleanly", consumer, e);
            }
        }
    }

    /**
     * Has the poller close the driver as it finishes, when the consumer closes while the poller is still running.
     * False if the poller has already finished: the caller closes the driver then.
     */
    synchronized boolean closeDriverOnExit() {
        if (exited) {
            return false;
        }
        closeDriverOnExit = true;
        return true;
    }

    /** Waits until the poller has finished; false if it is still running after {@code timeout}. */
    boolean awaitStopped(Duration timeout) {
        try {
            poller.join(Math.max(1, timeout.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !poller.isAlive();
    }

    /** How long stopping may take: running handlers finishing, the poll under way, and the last commit. */
    Duration stopTimeout() {
        return options.shutdownTimeout().plus(options.pollWait()).plusSeconds(5);
    }

    private void dispatch(List<DriverRecord> records) {
        for (DriverRecord record : records) {
            PartitionWork work = partitions.get(record.partition());
            if (work == null) {
                continue; // no longer owned: the new owner reads it from the committed offset
            }
            received.incrementAndGet();
            work.queue.add(record);
            work.startIfIdle();
        }
    }

    private void pauseAndResume() {
        List<Partition> pause = new ArrayList<>();
        List<Partition> resume = new ArrayList<>();
        for (PartitionWork work : partitions.values()) {
            int buffered = work.queue.size();
            if (!work.paused && buffered >= options.maxBufferedRecords()) {
                pause.add(work.partition);
                work.paused = true;
            } else if (work.paused && buffered <= options.maxBufferedRecords() / 2) {
                resume.add(work.partition);
                work.paused = false;
            }
        }
        if (!pause.isEmpty()) {
            driver.pause(pause);
        }
        if (!resume.isEmpty()) {
            driver.resume(resume);
        }
    }

    private void commitIfDue() {
        if (System.nanoTime() - lastCommit < options.commitInterval().toNanos()) {
            return;
        }
        lastCommit = System.nanoTime();
        Map<Partition, Long> offsets = uncommitted(partitions.values());
        if (offsets.isEmpty()) {
            return;
        }
        try {
            driver.commitAsync(offsets);
            markCommitted(offsets);
        } catch (RuntimeException e) {
            LOG.debug("Periodic commit on {} failed; the next one covers it", consumer, e);
        }
    }

    private void commitNow(Collection<PartitionWork> works) {
        Map<Partition, Long> offsets = uncommitted(works);
        if (offsets.isEmpty()) {
            return;
        }
        try {
            consumer.commitNow(offsets);
            markCommitted(offsets);
        } catch (RuntimeException e) {
            LOG.warn("Could not commit {} on {}; those records will be read again", offsets, consumer, e);
        }
    }

    private static Map<Partition, Long> uncommitted(Collection<PartitionWork> works) {
        Map<Partition, Long> offsets = new LinkedHashMap<>();
        for (PartitionWork work : works) {
            long next = work.committable;
            if (next > work.committed) {
                offsets.put(work.partition, next);
            }
        }
        return offsets;
    }

    private void markCommitted(Map<Partition, Long> offsets) {
        offsets.forEach((partition, next) -> {
            PartitionWork work = partitions.get(partition);
            if (work != null) {
                work.committed = Math.max(work.committed, next);
            }
        });
    }

    /** Lets handlers finish their current record, then commits what was handled. */
    private void stopAndCommit(Collection<PartitionWork> works) {
        works.forEach(PartitionWork::stop);
        long deadline = System.nanoTime() + options.shutdownTimeout().toNanos();
        for (PartitionWork work : works) {
            if (!work.awaitIdle(deadline)) {
                LOG.warn("Handler for {} on {} still running after {}", work.partition, consumer, options.shutdownTimeout());
            }
        }
        commitNow(works);
    }

    private void shutdown() {
        running = false;
        List<PartitionWork> works = new ArrayList<>(partitions.values());
        try {
            stopAndCommit(works);
            rewind(works);
        } finally {
            partitions.clear();
            handlers.shutdown();
        }
    }

    /**
     * Leaves the driver as a later listener on this member needs it: each partition read again from the first
     * record this listener read ahead but didn't handle, and none paused.
     */
    private void rewind(List<PartitionWork> works) {
        List<Partition> paused = new ArrayList<>();
        for (PartitionWork work : works) {
            DriverRecord next = work.queue.peek();
            try {
                if (next != null) {
                    driver.seek(work.partition, next.offset());
                }
            } catch (RuntimeException e) {
                LOG.debug("Could not rewind {} on {}; its next owner reads it from the committed offset",
                        work.partition, consumer, e);
            }
            if (work.paused) {
                paused.add(work.partition);
            }
        }
        if (!paused.isEmpty()) {
            try {
                driver.resume(paused);
            } catch (RuntimeException e) {
                LOG.debug("Could not resume {} on {}", paused, consumer, e);
            }
        }
    }

    /** Handles one record until it succeeds, is dead-lettered, or the partition stops; true if done with it. */
    private boolean handle(PartitionWork work, DriverRecord record) {
        for (int attempt = 1; ; attempt++) {
            if (work.stopped) {
                return false;
            }
            inFlight.incrementAndGet();
            Throwable failure;
            try {
                listener.onRecord(consumer.toReceived(record, attempt));
                handled.incrementAndGet();
                return true;
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Throwable e) {
                failure = e;
            } finally {
                inFlight.decrementAndGet();
            }
            failed.incrementAndGet();
            LOG.warn("Handler failed for {}@{} on {} (attempt {})", record.partition(), record.offset(), consumer,
                    attempt, failure);
            if (options.maxAttempts() > 0 && attempt >= options.maxAttempts() && deadLetter(record, failure)) {
                return true;
            }
            work.sleep(options.backoff(attempt));
        }
    }

    /**
     * Sends the record, as the listener saw it (a claim-checked value resolved), to the dead-letter producer,
     * whose own claim check applies. True if sent; false to keep retrying the record.
     */
    private boolean deadLetter(DriverRecord record, Throwable failure) {
        StreamProducer target = options.deadLetter();
        try {
            ReceivedRecord received = consumer.toReceived(record, 1);
            Map<String, String> headers = new LinkedHashMap<>(received.headers());
            headers.put(DEAD_LETTER_TOPIC, record.partition().topic());
            headers.put(DEAD_LETTER_PARTITION, Integer.toString(record.partition().partition()));
            headers.put(DEAD_LETTER_OFFSET, Long.toString(record.offset()));
            headers.put(DEAD_LETTER_ERROR, String.valueOf(failure));
            OutgoingRecord.Builder letter = OutgoingRecord.builder(received.value());
            received.key().ifPresent(letter::key);
            target.send(letter.build().withValue(received.value(), headers));
            deadLettered.incrementAndGet();
            return true;
        } catch (RuntimeException e) {
            LOG.warn("Could not dead-letter {}@{} to {}; retrying the record", record.partition(), record.offset(), target, e);
            return false;
        }
    }

    @Override
    public boolean isRunning() {
        return running && poller.isAlive();
    }

    @Override
    public ListenerStatistics statistics() {
        return new ListenerStatistics(received.get(), handled.get(), failed.get(), deadLettered.get(), inFlight.get(),
                pollErrors.get(), partitions.size());
    }

    @Override
    public void close() {
        running = false;
        partitions.values().forEach(PartitionWork::wake);
        driver.wakeup();
        if (Thread.currentThread() == poller) {
            return;
        }
        awaitStopped(stopTimeout());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String toString() {
        return "StreamListener[" + consumer + ", " + statistics() + "]";
    }

    private final class Rebalance implements RebalanceListener {

        @Override
        public void onAssigned(Collection<Partition> assigned) {
            assigned.forEach(p -> partitions.computeIfAbsent(p, PartitionWork::new));
        }

        @Override
        public void onRevoked(Collection<Partition> revoked) {
            stopAndCommit(remove(revoked));
        }

        @Override
        public void onLost(Collection<Partition> lost) {
            remove(lost).forEach(PartitionWork::stop);
        }

        private List<PartitionWork> remove(Collection<Partition> gone) {
            List<PartitionWork> works = new ArrayList<>();
            for (Partition partition : gone) {
                PartitionWork work = partitions.remove(partition);
                if (work != null) {
                    works.add(work);
                }
            }
            return works;
        }
    }

    /** One owned partition: its read-ahead buffer, the worker draining it, and how far it was handled. */
    private final class PartitionWork {

        final Partition partition;
        final ConcurrentLinkedQueue<DriverRecord> queue = new ConcurrentLinkedQueue<>();
        final AtomicBoolean active = new AtomicBoolean();
        volatile boolean stopped;
        /** The next offset to read once everything handled so far is committed. */
        volatile long committable = -1;
        /** Polling thread only. */
        long committed = -1;
        boolean paused;

        PartitionWork(Partition partition) {
            this.partition = Objects.requireNonNull(partition);
        }

        void startIfIdle() {
            if (!stopped && running && !queue.isEmpty() && active.compareAndSet(false, true)) {
                handlers.execute(this::drain);
            }
        }

        private void drain() {
            try {
                while (!stopped && running) {
                    DriverRecord record = queue.peek();
                    if (record == null || !handle(this, record)) {
                        break;
                    }
                    queue.poll();
                    committable = record.offset() + 1;
                }
            } finally {
                active.set(false);
                synchronized (this) {
                    notifyAll();
                }
            }
            startIfIdle(); // a record may have arrived after the last peek
        }

        void stop() {
            stopped = true;
            wake();
        }

        synchronized void wake() {
            notifyAll();
        }

        /** Waits out a retry backoff, returning early when the partition stops or the listener closes. */
        synchronized void sleep(Duration backoff) {
            long deadline = System.nanoTime() + backoff.toNanos();
            try {
                while (!stopped && running) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        return;
                    }
                    wait(Math.max(1, left / 1_000_000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopped = true;
            }
        }

        synchronized boolean awaitIdle(long deadlineNanos) {
            try {
                while (active.get()) {
                    long left = deadlineNanos - System.nanoTime();
                    if (left <= 0) {
                        return false;
                    }
                    wait(Math.max(1, left / 1_000_000));
                }
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
