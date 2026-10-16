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
package dev.halyx.reactor;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.events.CloudEvent;
import dev.halyx.messaging.ConsumerOptions;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.parameters.ParameterChanges;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.stream.ListenerOptions;
import dev.halyx.stream.OutgoingRecord;
import dev.halyx.stream.StartPosition;
import dev.halyx.stream.StreamConfig;
import dev.halyx.stream.StreamConsumerConfig;
import dev.halyx.stream.StreamProducerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The streaming adapters' guarantees, on the in-memory provider: settling after processing, redelivery of
 * failures, nacks on cancel, bounded work in flight, CloudEvent decoding, ordered commits per partition, and
 * watches that live as long as their subscriber.
 */
class ReactiveStreamingTest {

    private static final ConsumerOptions FAST = ConsumerOptions.DEFAULTS.withConcurrency(4)
            .withBackoff(Duration.ofMillis(20), Duration.ofMillis(50)).withPollWait(Duration.ofMillis(200));

    private Halyx halyx;
    private ReactiveHalyx rx;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
        rx = ReactiveHalyx.withVirtualThreads(halyx);
    }

    @AfterEach
    void stop() {
        rx.close();
        halyx.close();
    }

    private ReactiveSubscription subscription(String topic) {
        return rx.provider("mem").subscription(SubscriptionConfig.builder(topic + "-sub").topic(topic)
                .property("memory.lease", "PT2S").build());
    }

    private void publish(String topic, int count) {
        IntStream.range(0, count).forEach(i -> halyx.provider("mem").publisher(topic).publish("m-" + i));
    }

    /** Everything left on the subscription, acking it, within a short wait. */
    private List<String> drain(ReactiveSubscription subscription) {
        List<String> left = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        while (System.nanoTime() < deadline) {
            for (ReceivedMessage m : subscription.receive(10, Duration.ofMillis(200)).block()) {
                left.add(m.bodyAsString());
                m.ack();
            }
        }
        return left;
    }

    @Test
    void processAcksAfterTheHandlerAndRedeliversFailures() {
        ReactiveSubscription orders = subscription("orders");
        publish("orders", 20);
        Map<String, Integer> attempts = new ConcurrentHashMap<>();

        List<String> handled = orders.process(FAST, message -> Mono.fromCallable(() -> {
                    String body = message.bodyAsString();
                    if (attempts.merge(body, 1, Integer::sum) == 1 && body.equals("m-7")) {
                        throw new IllegalStateException("first attempt fails");
                    }
                    return body;
                }))
                .take(20).collectList().block(Duration.ofSeconds(10));

        assertThat(handled).hasSize(20).doesNotHaveDuplicates().contains("m-7");
        assertThat(attempts).containsEntry("m-7", 2);
        assertThat(drain(orders)).as("everything was acked").isEmpty();
    }

    @Test
    void inFlightWorkIsBoundedByTheConsumersConcurrency() {
        ReactiveSubscription orders = subscription("bounded");
        publish("bounded", 30);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        Long count = orders.process(FAST, message -> Mono.delay(Duration.ofMillis(20))
                        .doOnSubscribe(s -> peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max))
                        // before completion reaches the ack, which lets the consumer take the next message
                        .doOnTerminate(inFlight::decrementAndGet))
                .take(30).count().block(Duration.ofSeconds(10));

        assertThat(count).isEqualTo(30);
        assertThat(peak.get()).isLessThanOrEqualTo(4).isGreaterThan(1);
    }

    @Test
    void aSlowSubscriberSlowsDelivery() {
        ReactiveSubscription orders = subscription("slow");
        publish("slow", 20);

        // Ask for two, and settle neither: the consumer must not take more than its concurrency meanwhile.
        StepVerifier.create(orders.deliveries(FAST), 2)
                .expectNextCount(2)
                .expectNoEvent(Duration.ofMillis(500))
                .thenCancel()
                .verify(Duration.ofSeconds(5));

        assertThat(drain(orders)).as("cancelled deliveries were nacked, the rest never taken").hasSize(20);
    }

    @Test
    void cancellingNacksWhatIsUnsettledAndStopsTheConsumerPromptly() {
        ReactiveSubscription orders = subscription("cancelled");
        publish("cancelled", 5);
        long start = System.nanoTime();

        Delivery<ReceivedMessage> first = orders.deliveries(FAST).next().block(Duration.ofSeconds(5));

        assertThat(first).isNotNull();
        assertThat(first.isSettled()).as("nacked on cancel").isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
        assertThat(drain(orders)).hasSize(5).contains(first.value().bodyAsString());
    }

    @Test
    void manualSettlementAcksAndNacks() {
        ReactiveSubscription orders = subscription("manual");
        publish("manual", 3);
        AtomicInteger nacked = new AtomicInteger();

        List<String> acked = orders.deliveries(FAST)
                .doOnNext(d -> {
                    if (d.value().bodyAsString().equals("m-1") && nacked.getAndIncrement() == 0) {
                        d.nack(new IllegalStateException("not yet"));
                    } else {
                        d.ack();
                    }
                })
                .filter(Delivery::isSettled)
                .map(d -> d.value().bodyAsString())
                .take(4).collectList().block(Duration.ofSeconds(10));

        assertThat(acked).containsOnly("m-0", "m-1", "m-2").filteredOn("m-1"::equals).hasSize(2);
        assertThat(drain(orders)).isEmpty();
    }

    @Test
    void eventsAreDecodedAndOtherMessagesNacked() {
        ReactiveSubscription inbox = subscription("events");
        ReactiveEventPublisher bus = rx.provider("mem").eventPublisher("events");
        CloudEvent placed = CloudEvent.builder("com.acme.order.placed", "/orders").json("{\"total\":1}").build();
        bus.publish(placed).block();
        halyx.provider("mem").publisher("events").publish("not an event");
        AtomicInteger notEvents = new AtomicInteger();

        CloudEvent received = inbox.events(FAST)
                .doOnDiscard(Object.class, o -> notEvents.incrementAndGet())
                .flatMap(d -> {
                    d.ack();
                    return Mono.just(d.value());
                })
                .next().block(Duration.ofSeconds(10));

        assertThat(received).isEqualTo(placed);
        List<String> left = drain(inbox);
        assertThat(left).as("the plain message was nacked, not acked").containsExactly("not an event");
    }

    @Test
    void streamRecordsCommitInOrderPerPartitionAndFailuresRetry() {
        StreamConfig cluster = StreamConfig.builder("memory:9092").build();
        ReactiveStreamProducer producer = rx.provider("mem").streamProducer(StreamProducerConfig.of(cluster, "ledger"));
        for (int i = 0; i < 30; i++) {
            producer.send(OutgoingRecord.of("account-" + (i % 3), "entry-" + i)).block();
        }
        ReactiveStreamConsumer consumer = rx.provider("mem").streamConsumer(StreamConsumerConfig.builder(cluster, "ledger", "billing")
                .startPosition(StartPosition.EARLIEST).build());
        Map<String, List<Integer>> perKey = new ConcurrentHashMap<>();
        AtomicInteger failures = new AtomicInteger();
        ListenerOptions options = ListenerOptions.DEFAULTS.withPollWait(Duration.ofMillis(100))
                .withBackoff(Duration.ofMillis(20), Duration.ofMillis(50)).withCommitInterval(Duration.ofMillis(50));

        Long handled = consumer.process(options, record -> Mono.fromCallable(() -> {
                    int n = Integer.parseInt(record.valueAsString().substring("entry-".length()));
                    if (n == 4 && failures.get() == 0) {
                        failures.incrementAndGet();
                        throw new IllegalStateException("retry me");
                    }
                    perKey.computeIfAbsent(record.key().orElseThrow(), k -> new ArrayList<>()).add(n);
                    return n;
                }))
                .take(30).count().block(Duration.ofSeconds(15));

        assertThat(handled).isEqualTo(30);
        assertThat(failures.get()).isEqualTo(1);
        perKey.values().forEach(entries -> assertThat(entries).isSorted().doesNotHaveDuplicates());

        // A second member of the group starts where the commits left off: nothing to read.
        ReactiveStreamConsumer again = rx.provider("mem").streamConsumer(StreamConsumerConfig.builder(cluster, "ledger", "billing")
                .startPosition(StartPosition.EARLIEST).build());
        StepVerifier.create(again.records(options)).expectSubscription()
                .expectNoEvent(Duration.ofMillis(500)).thenCancel().verify(Duration.ofSeconds(5));
    }

    @Test
    void parameterWatchesLiveAsLongAsTheirSubscriber() {
        ReactiveParameterStore store = rx.provider("mem").parameterStore(ParameterStoreConfig.secrets().prefix("app").build());

        StepVerifier.create(store.watch("flags", Duration.ofMillis(50)).take(1))
                .then(() -> store.put("flags/beta", "on").block())
                .assertNext(changes -> assertThat(changes.added()).containsKey("flags/beta"))
                .verifyComplete();

        List<ParameterChanges> afterCancel = new ArrayList<>();
        var watching = store.watch("flags", Duration.ofMillis(50)).subscribe(afterCancel::add);
        watching.dispose();
        store.put("flags/gamma", "on").block();
        StepVerifier.create(Flux.interval(Duration.ofMillis(300)).take(1)).expectNextCount(1).verifyComplete();
        assertThat(afterCancel).isEmpty();
    }
}
