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
package dev.halyx.tck;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import dev.halyx.CloudContext;
import dev.halyx.error.InvalidArgumentException;
import dev.halyx.error.UnsupportedFeatureException;
import dev.halyx.messaging.ClaimCheck;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.AsyncStreamConsumer;
import dev.halyx.stream.AsyncStreamProducer;
import dev.halyx.stream.ListenerOptions;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.ReceivedRecord;
import dev.halyx.stream.SendResult;
import dev.halyx.stream.StartPosition;
import dev.halyx.stream.StreamCapability;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumer;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamListener;
import dev.halyx.stream.StreamProducer;
import dev.halyx.stream.StreamProducerConfig;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for streams. Every provider extends this class and supplies a context, a cluster and a way
 * to create topics; each test creates its own topics with {@value #PARTITIONS} partitions and its own consumer
 * groups, so tests never interfere and the cluster can be shared. Delivery is at least once, so tests tolerate
 * records read twice and assert on what must hold: nothing lost, order kept per key, committed positions
 * honoured.
 */
public abstract class StreamConformanceTest {

    protected static final int PARTITIONS = 3;

    private final List<StreamConsumer> consumers = new CopyOnWriteArrayList<>();
    private String topic;

    /** The provider context under test. */
    protected abstract CloudContext context();

    /** Bootstrap servers, auth and properties of the cluster under test. */
    protected abstract StreamConfig.Builder cluster();

    /** The producer under test for {@code config}; override to test a wrapper around {@code context()}'s producers. */
    protected StreamProducer newProducer(StreamProducerConfig config) {
        return context().streamProducer(config);
    }

    /** The consumer under test for {@code config}; override to test a wrapper around {@code context()}'s consumers. */
    protected StreamConsumer newConsumer(StreamConsumerConfig config) {
        return context().streamConsumer(config);
    }

    /** Creates a topic; Halyx never creates topics itself. */
    protected abstract void createTopic(String topic, int partitions);

    /** A blob store for claim-check tests, or null to skip them. */
    protected BlobStore claimCheckStore() {
        return null;
    }

    /** How long to wait for records and group changes; real clusters need longer than emulators. */
    protected Duration timeout() {
        return Duration.ofSeconds(30);
    }

    /** Tests to skip because the environment does not implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newTopic(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        topic = newTopicName();
        createTopic(topic, PARTITIONS);
    }

    @AfterEach
    void closeConsumers() {
        consumers.forEach(StreamConsumer::close);
        consumers.clear();
    }

    private static String newTopicName() {
        return "tck-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private StreamConfig config() {
        return cluster().build();
    }

    private StreamProducer producer() {
        return producer(topic, b -> b);
    }

    private StreamProducer producer(String name, UnaryOperator<StreamProducerConfig.Builder> customize) {
        return newProducer(customize.apply(StreamProducerConfig.builder(config(), name)).build());
    }

    private StreamConsumer consumer(String group) {
        return consumer(group, b -> b.startPosition(StartPosition.EARLIEST));
    }

    private StreamConsumer consumer(String group, UnaryOperator<StreamConsumerConfig.Builder> customize) {
        StreamConsumer consumer = newConsumer(customize.apply(
                StreamConsumerConfig.builder(config(), topic, group)).build());
        consumers.add(consumer);
        return consumer;
    }

    private static String group() {
        return "tck-group-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Polls until {@code count} records arrived, failing after the timeout. */
    private List<ReceivedRecord> pollAtLeast(StreamConsumer consumer, int count) {
        List<ReceivedRecord> records = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout());
        while (records.size() < count) {
            assertThat(Instant.now()).as("%d of %d records arrived", records.size(), count).isBefore(deadline);
            records.addAll(consumer.poll(Duration.ofMillis(500)));
        }
        return records;
    }

    /** Polls for {@code quiet}, returning whatever arrived. */
    private static List<ReceivedRecord> pollFor(StreamConsumer consumer, Duration quiet) {
        List<ReceivedRecord> records = new ArrayList<>();
        Instant end = Instant.now().plus(quiet);
        while (Instant.now().isBefore(end)) {
            records.addAll(consumer.poll(Duration.ofMillis(250)));
        }
        return records;
    }

    private void await(String what, BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(timeout());
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as(what).isBefore(deadline);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ListenerOptions fastRetries() {
        return ListenerOptions.DEFAULTS.withBackoff(Duration.ofMillis(20), Duration.ofMillis(100))
                .withPollWait(Duration.ofMillis(200)).withCommitInterval(Duration.ofMillis(200));
    }

    private static List<String> values(List<ReceivedRecord> records) {
        return records.stream().map(ReceivedRecord::valueAsString).toList();
    }

    // --- sending ---------------------------------------------------------------------------------

    @Test
    void sentRecordArrivesWithKeyValueAndHeaders() {
        SendResult sent = producer().send(OutgoingRecord.builder("hello").key("greeting")
                .header("type", "greeting").header("tenant", "acme").build());

        ReceivedRecord record = pollAtLeast(consumer(group()), 1).getFirst();

        assertThat(sent.topic()).isEqualTo(topic);
        assertThat(sent.partition()).isBetween(0, PARTITIONS - 1);
        assertThat(record.topic()).isEqualTo(topic);
        assertThat(record.partition()).isEqualTo(sent.partition());
        assertThat(record.offset()).isEqualTo(sent.offset());
        assertThat(record.key()).contains("greeting");
        assertThat(record.valueAsString()).isEqualTo("hello");
        assertThat(record.headers()).containsEntry("type", "greeting").containsEntry("tenant", "acme");
        assertThat(record.deliveryAttempt()).isEqualTo(1);
        assertThat(record.timestamp()).isPresent();
    }

    @Test
    void binaryValuesArriveUnchanged() {
        byte[] value = new byte[64 * 1024];
        new Random(5).nextBytes(value);
        producer().send(OutgoingRecord.builder(value).build());

        assertThat(pollAtLeast(consumer(group()), 1).getFirst().value()).isEqualTo(value);
    }

    @Test
    void recordsWithoutKeyArrive() {
        producer().sendAll(List.of(OutgoingRecord.of("a"), OutgoingRecord.of("b"), OutgoingRecord.of("c")));

        List<ReceivedRecord> records = pollAtLeast(consumer(group()), 3);

        assertThat(values(records)).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(records).allSatisfy(r -> assertThat(r.key()).isEmpty());
    }

    @Test
    void sameKeyGoesToOnePartitionWithIncreasingOffsets() {
        StreamProducer producer = producer();
        List<SendResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            results.add(producer.send("customer-42", "event-" + i));
        }

        assertThat(results).extracting(SendResult::partition).containsOnly(results.getFirst().partition());
        for (int i = 1; i < results.size(); i++) {
            assertThat(results.get(i).offset()).isGreaterThan(results.get(i - 1).offset());
        }
    }

    @Test
    void sendAllReturnsResultsInOrder() {
        List<OutgoingRecord> records = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            records.add(OutgoingRecord.of("k" + (i % 4), "v" + i));
        }
        List<SendResult> results = producer().sendAll(records);

        assertThat(results).hasSize(20);
        List<ReceivedRecord> received = pollAtLeast(consumer(group()), 20);
        for (int i = 0; i < 20; i++) {
            SendResult result = results.get(i);
            String value = "v" + i;
            assertThat(received).anySatisfy(r -> {
                assertThat(r.valueAsString()).isEqualTo(value);
                assertThat(r.partition()).isEqualTo(result.partition());
                assertThat(r.offset()).isEqualTo(result.offset());
            });
        }
    }

    @Test
    void recordsWithTheSameKeyStayInOrder() {
        List<OutgoingRecord> records = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            records.add(OutgoingRecord.of("key-" + (i % 3), Integer.toString(i)));
        }
        producer().sendAll(records);

        List<ReceivedRecord> received = pollAtLeast(consumer(group()), 60);

        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        received.forEach(r -> byKey.computeIfAbsent(r.key().orElseThrow(), k -> new ArrayList<>())
                .add(Integer.parseInt(r.valueAsString())));
        assertThat(byKey).hasSize(3);
        byKey.values().forEach(sequence -> assertThat(sequence).isSorted().hasSize(20));
    }

    @Test
    void partitionCountMatchesTheTopic() {
        assertThat(producer().partitionCount()).isEqualTo(PARTITIONS);
    }

    @Test
    void reservedHeadersAreRejected() {
        assertThatThrownBy(() -> producer().send(OutgoingRecord.builder("x").header("halyx-anything", "1").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedRecordWithoutClaimCheckIsRejected() {
        byte[] value = new byte[2 * 1024 * 1024];

        assertThatThrownBy(() -> producer().send(OutgoingRecord.builder(value).build()))
                .isInstanceOf(InvalidArgumentException.class);
    }

    @Test
    void largeValuesTravelThroughAClaimCheck() {
        BlobStore store = claimCheckStore();
        assumeTrue(store != null, "no claim-check store");
        ClaimCheck claims = new ClaimCheck(store, 1024, "tck-claims/" + topic + "/");
        byte[] value = new byte[2 * 1024 * 1024];
        new Random(7).nextBytes(value);

        producer(topic, b -> b.claimCheck(claims)).send(OutgoingRecord.builder(value).key("big").header("type", "blob").build());
        ReceivedRecord record = pollAtLeast(consumer(group(), b -> b.startPosition(StartPosition.EARLIEST)
                .claimCheck(claims)), 1).getFirst();

        assertThat(record.value()).isEqualTo(value);
        assertThat(record.headers()).containsEntry("type", "blob").doesNotContainKey(ClaimCheck.ATTRIBUTE);
    }

    @Test
    void asyncSendsArriveLikeBlockingOnes() throws Exception {
        AsyncStreamProducer async = producer().async();
        List<OutgoingRecord> batch = List.of(OutgoingRecord.of("k", "a1"), OutgoingRecord.of("k", "a2"),
                OutgoingRecord.builder("a3").key("k").header("type", "async").build());

        List<SendResult> results = async.sendAll(batch).get(60, TimeUnit.SECONDS);
        SendResult single = async.send("k", "a4").get(60, TimeUnit.SECONDS);

        assertThat(results).hasSize(3).allSatisfy(result -> assertThat(result.topic()).isEqualTo(topic));
        assertThat(single.offset()).isGreaterThan(results.getLast().offset());
        assertThat(pollAtLeast(consumer(group()), 4)).extracting(ReceivedRecord::valueAsString)
                .containsExactly("a1", "a2", "a3", "a4");
    }

    @Test
    void asyncLargeValuesTravelThroughAClaimCheck() throws Exception {
        BlobStore store = claimCheckStore();
        assumeTrue(store != null, "no claim-check store");
        ClaimCheck claims = new ClaimCheck(store, 1024, "tck-claims/" + topic + "/async/");
        byte[] value = new byte[256 * 1024];
        new Random(9).nextBytes(value);

        producer(topic, b -> b.claimCheck(claims)).async().send(OutgoingRecord.builder(value).key("big").build())
                .get(60, TimeUnit.SECONDS);
        ReceivedRecord record = pollAtLeast(consumer(group(), b -> b.startPosition(StartPosition.EARLIEST)
                .claimCheck(claims)), 1).getFirst();

        assertThat(record.value()).isEqualTo(value);
    }

    @Test
    void idempotentProducersNeedTheCapability() {
        StreamProducer plain = producer();
        if (plain.capabilities().supports(StreamCapability.IDEMPOTENCE)) {
            StreamProducer idempotent = producer(topic, b -> b.idempotent(true));
            idempotent.send("k", "once");
            assertThat(values(pollAtLeast(consumer(group()), 1))).containsExactly("once");
        } else {
            assertThatThrownBy(() -> producer(topic, b -> b.idempotent(true)))
                    .isInstanceOf(UnsupportedFeatureException.class);
        }
    }

    @Test
    void transactionalSendAllIsVisibleToReadCommittedConsumers() {
        StreamProducer plain = producer();
        if (!plain.capabilities().supports(StreamCapability.TRANSACTIONS)) {
            assertThatThrownBy(() -> producer(topic, b -> b.transactional(true)))
                    .isInstanceOf(UnsupportedFeatureException.class);
            return;
        }
        StreamProducer transactional = producer(topic, b -> b.transactional(true));
        transactional.sendAll(List.of(OutgoingRecord.of("k1", "t1"), OutgoingRecord.of("k2", "t2"), OutgoingRecord.of("k3", "t3")));
        transactional.send("k1", "t4");

        StreamConsumer readCommitted = consumer(group(), b -> b.startPosition(StartPosition.EARLIEST).readCommitted(true));

        assertThat(values(pollAtLeast(readCommitted, 4))).containsExactlyInAnyOrder("t1", "t2", "t3", "t4");
    }

    // --- consumer groups -------------------------------------------------------------------------

    @Test
    void committedPositionIsWhereTheGroupResumes() {
        StreamProducer producer = producer();
        for (int i = 0; i < 5; i++) {
            producer.send("k" + i, "first-" + i);
        }
        String group = group();
        StreamConsumer first = consumer(group);
        assertThat(pollAtLeast(first, 5)).hasSize(5);
        first.commit();
        first.close();

        for (int i = 0; i < 3; i++) {
            producer.send("k" + i, "second-" + i);
        }
        List<ReceivedRecord> resumed = pollAtLeast(consumer(group), 3);
        resumed.addAll(pollFor(consumers.getLast(), Duration.ofSeconds(1)));

        assertThat(values(resumed)).containsExactlyInAnyOrder("second-0", "second-1", "second-2");
    }

    @Test
    void asyncPollsAndCommitsRunInOrder() throws Exception {
        producer().sendAll(List.of(OutgoingRecord.of("k", "p1"), OutgoingRecord.of("k", "p2"), OutgoingRecord.of("k", "p3")));
        String group = group();
        StreamConsumer first = consumer(group);
        AsyncStreamConsumer async = first.async();

        List<ReceivedRecord> records = new ArrayList<>();
        Instant deadline = Instant.now().plus(timeout());
        while (records.size() < 3) {
            assertThat(Instant.now()).as("%d of 3 records arrived", records.size()).isBefore(deadline);
            // queued together: each runs once the one before has finished, never two at once on the consumer
            CompletableFuture<List<ReceivedRecord>> one = async.poll(Duration.ofMillis(250));
            CompletableFuture<List<ReceivedRecord>> two = async.poll(Duration.ofMillis(250));
            records.addAll(one.get(60, TimeUnit.SECONDS));
            records.addAll(two.get(60, TimeUnit.SECONDS));
        }
        async.commit().get(60, TimeUnit.SECONDS);
        first.close();
        producer().send("k", "p4");

        assertThat(values(records)).containsExactly("p1", "p2", "p3");
        assertThat(values(pollAtLeast(consumer(group), 1))).as("the group resumes after the async commit")
                .containsExactly("p4");
    }

    @Test
    void uncommittedRecordsAreReadAgain() {
        producer().sendAll(List.of(OutgoingRecord.of("a"), OutgoingRecord.of("b")));
        String group = group();
        StreamConsumer first = consumer(group);
        assertThat(pollAtLeast(first, 2)).hasSize(2);
        first.close();

        assertThat(values(pollAtLeast(consumer(group), 2))).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void everyGroupReadsEveryRecord() {
        producer().sendAll(List.of(OutgoingRecord.of("a"), OutgoingRecord.of("b"), OutgoingRecord.of("c")));

        assertThat(values(pollAtLeast(consumer(group()), 3))).containsExactlyInAnyOrder("a", "b", "c");
        assertThat(values(pollAtLeast(consumer(group()), 3))).containsExactlyInAnyOrder("a", "b", "c");
    }

    @Test
    void latestStartSkipsEarlierRecords() {
        StreamProducer producer = producer();
        producer.send("k", "before");
        List<ReceivedRecord> received = new CopyOnWriteArrayList<>();
        StreamListener listener = consumer(group(), b -> b.startPosition(StartPosition.LATEST))
                .listen(received::add, fastRetries());
        await("partitions assigned", () -> listener.statistics().assignedPartitions() == PARTITIONS);

        // A new member fixes its starting position on its first fetch, which can come after assignment; keep
        // sending until a record arrives, then check the one sent before it started never does.
        Instant deadline = Instant.now().plus(timeout());
        while (received.isEmpty()) {
            assertThat(Instant.now()).as("a new record arrived").isBefore(deadline);
            producer.send("k", "after");
            sleep(Duration.ofMillis(500));
        }
        assertThat(values(received)).isNotEmpty().allMatch("after"::equals);
    }

    @Test
    void membersOfAGroupShareThePartitions() {
        String group = group();
        Map<String, Set<Integer>> partitionsByMember = new ConcurrentHashMap<>();
        Set<String> values = ConcurrentHashMap.newKeySet();
        StreamListener one = consumer(group).listen(r -> {
            partitionsByMember.computeIfAbsent("one", m -> ConcurrentHashMap.newKeySet()).add(r.partition());
            values.add(r.valueAsString());
        }, fastRetries());
        StreamListener two = consumer(group).listen(r -> {
            partitionsByMember.computeIfAbsent("two", m -> ConcurrentHashMap.newKeySet()).add(r.partition());
            values.add(r.valueAsString());
        }, fastRetries());
        await("both members own partitions", () -> one.statistics().assignedPartitions() > 0
                && two.statistics().assignedPartitions() > 0
                && one.statistics().assignedPartitions() + two.statistics().assignedPartitions() == PARTITIONS);

        List<OutgoingRecord> records = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            records.add(OutgoingRecord.of("key-" + i, "v" + i));
        }
        producer().sendAll(records);

        await("every record handled", () -> values.size() == 60);
        assertThat(partitionsByMember.get("one")).doesNotContainAnyElementsOf(partitionsByMember.get("two"));
    }

    @Test
    void pollAndListenCannotBeMixed() {
        StreamConsumer polled = consumer(group());
        polled.poll(Duration.ofMillis(100));
        assertThatThrownBy(() -> polled.listen(r -> { })).isInstanceOf(IllegalStateException.class);

        StreamConsumer listening = consumer(group());
        listening.listen(r -> { }, fastRetries());
        assertThatThrownBy(() -> listening.poll(Duration.ofMillis(100))).isInstanceOf(IllegalStateException.class);
    }

    // --- listeners -------------------------------------------------------------------------------

    @Test
    void listenerHandlesEveryRecord() {
        List<OutgoingRecord> records = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            records.add(OutgoingRecord.of("key-" + (i % 7), "v" + i));
        }
        producer().sendAll(records);
        Set<String> handled = ConcurrentHashMap.newKeySet();

        StreamListener listener = consumer(group()).listen(r -> handled.add(r.valueAsString()), fastRetries());

        await("every record handled", () -> handled.size() == 50);
        await("statistics caught up", () -> listener.statistics().handled() >= 50);
        assertThat(listener.statistics().received()).isGreaterThanOrEqualTo(50);
        assertThat(listener.statistics().assignedPartitions()).isEqualTo(PARTITIONS);
    }

    @Test
    void failedRecordIsRetriedBeforeTheRestOfItsPartition() {
        producer().sendAll(List.of(OutgoingRecord.of("same", "a"), OutgoingRecord.of("same", "b"),
                OutgoingRecord.of("same", "c")));
        List<String> seen = new CopyOnWriteArrayList<>();
        AtomicInteger finalAttempt = new AtomicInteger();

        StreamListener listener = consumer(group()).listen(r -> {
            seen.add(r.valueAsString());
            if (r.valueAsString().equals("b")) {
                if (r.deliveryAttempt() < 3) {
                    throw new IllegalStateException("not yet");
                }
                finalAttempt.set(r.deliveryAttempt());
            }
        }, fastRetries());

        await("c handled", () -> seen.contains("c"));
        assertThat(seen).containsExactly("a", "b", "b", "b", "c");
        assertThat(finalAttempt.get()).isEqualTo(3);
        await("failures counted", () -> listener.statistics().failed() == 2);
    }

    @Test
    void aFailingPartitionDoesNotHoldBackTheOthers() {
        StreamProducer producer = producer();
        int stuckPartition = producer.send("stuck", "poison").partition();
        List<String> others = new ArrayList<>();
        for (int i = 0; others.size() < 10 && i < 1000; i++) {
            SendResult result = producer.send("key-" + i, "ok-" + i);
            if (result.partition() != stuckPartition) {
                others.add("ok-" + i);
            }
        }
        Set<String> handled = ConcurrentHashMap.newKeySet();

        consumer(group()).listen(r -> {
            if (r.valueAsString().equals("poison")) {
                throw new IllegalStateException("poison");
            }
            handled.add(r.valueAsString());
        }, fastRetries());

        await("records on other partitions handled", () -> handled.containsAll(others));
    }

    @Test
    void exhaustedRecordGoesToTheDeadLetterTopic() {
        String deadLetterTopic = newTopicName() + "-dlt";
        createTopic(deadLetterTopic, 1);
        StreamProducer deadLetters = producer(deadLetterTopic, b -> b);
        producer().sendAll(List.of(OutgoingRecord.builder("poison").key("same").header("type", "bad").build(),
                OutgoingRecord.of("same", "fine")));
        List<String> handled = new CopyOnWriteArrayList<>();

        StreamListener listener = consumer(group()).listen(r -> {
            if (r.valueAsString().equals("poison")) {
                throw new IllegalStateException("cannot handle");
            }
            handled.add(r.valueAsString());
        }, fastRetries().withDeadLetter(deadLetters, 2));

        await("the next record handled", () -> handled.contains("fine"));
        assertThat(listener.statistics().deadLettered()).isEqualTo(1);
        StreamConsumer deadLetterReader = newConsumer(StreamConsumerConfig
                .builder(config(), deadLetterTopic, group()).startPosition(StartPosition.EARLIEST).build());
        consumers.add(deadLetterReader);
        ReceivedRecord letter = pollAtLeast(deadLetterReader, 1).getFirst();
        assertThat(letter.valueAsString()).isEqualTo("poison");
        assertThat(letter.key()).contains("same");
        assertThat(letter.headers()).containsEntry("type", "bad")
                .containsEntry("halyx-dead-letter-topic", topic)
                .containsKey("halyx-dead-letter-offset")
                .hasEntrySatisfying("halyx-dead-letter-error", error -> assertThat(error).contains("cannot handle"));
    }

    @Test
    void handledRecordsAreCommittedWhenTheListenerStops() {
        StreamProducer producer = producer();
        List<OutgoingRecord> records = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            records.add(OutgoingRecord.of("key-" + i, "old-" + i));
        }
        producer.sendAll(records);
        String group = group();
        Set<String> handled = ConcurrentHashMap.newKeySet();
        StreamConsumer first = consumer(group, b -> b.startPosition(StartPosition.EARLIEST));
        first.listen(r -> handled.add(r.valueAsString()), fastRetries().withCommitInterval(Duration.ofHours(1)));
        await("every record handled", () -> handled.size() == 10);
        first.close();

        producer.send("key-0", "new");
        List<ReceivedRecord> next = pollAtLeast(consumer(group), 1);
        next.addAll(pollFor(consumers.getLast(), Duration.ofSeconds(1)));

        assertThat(values(next)).containsExactly("new");
    }

    @Test
    void closingTheListenerLetsTheRunningHandlerFinish() {
        producer().send("k", "slow");
        AtomicInteger finished = new AtomicInteger();
        List<String> started = new CopyOnWriteArrayList<>();
        StreamListener listener = consumer(group()).listen(r -> {
            started.add(r.valueAsString());
            Thread.sleep(500);
            finished.incrementAndGet();
        }, fastRetries());
        await("handler started", () -> !started.isEmpty());

        listener.close();

        assertThat(finished.get()).isEqualTo(1);
        assertThat(listener.isRunning()).isFalse();
    }

    @Test
    void handlerSeesEveryRecordOfItsPartitionInOrder() {
        List<OutgoingRecord> records = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            records.add(OutgoingRecord.of("key-" + (i % 9), Integer.toString(i)));
        }
        producer().sendAll(records);
        Map<Integer, List<Long>> offsets = new ConcurrentHashMap<>();
        Set<Integer> concurrent = Collections.synchronizedSet(new HashSet<>());
        AtomicInteger total = new AtomicInteger();
        Consumer<ReceivedRecord> track = r -> offsets.computeIfAbsent(r.partition(),
                p -> new CopyOnWriteArrayList<>()).add(r.offset());

        consumer(group()).listen(r -> {
            if (!concurrent.add(r.partition())) {
                throw new AssertionError("two handlers ran at once on partition " + r.partition());
            }
            try {
                track.accept(r);
                Thread.sleep(2);
            } finally {
                concurrent.remove(r.partition());
                total.incrementAndGet();
            }
        }, fastRetries());

        await("every record handled", () -> total.get() >= 90);
        offsets.values().forEach(sequence -> assertThat(sequence).isSorted());
    }
}
