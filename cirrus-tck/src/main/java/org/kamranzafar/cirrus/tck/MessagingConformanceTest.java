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
package org.kamranzafar.cirrus.tck;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.kamranzafar.cirrus.error.InvalidArgumentException;
import org.kamranzafar.cirrus.messaging.ClaimCheck;
import org.kamranzafar.cirrus.messaging.ConsumerOptions;
import org.kamranzafar.cirrus.messaging.MessageConsumer;
import org.kamranzafar.cirrus.messaging.MessagingCapability;
import org.kamranzafar.cirrus.messaging.OutgoingMessage;
import org.kamranzafar.cirrus.messaging.Publisher;
import org.kamranzafar.cirrus.messaging.ReceivedMessage;
import org.kamranzafar.cirrus.messaging.Subscription;
import org.kamranzafar.cirrus.storage.BlobStore;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for messaging. Every provider extends this class and creates, per test, a fresh topic
 * with one subscription receiving from it. Delivery is at least once on every provider, so tests tolerate
 * duplicates and assert on what must hold: nothing lost, settled messages not redelivered, leases honoured.
 * Leases should be as short as the provider allows, since several tests wait for one to run out.
 */
public abstract class MessagingConformanceTest {

    /** A topic and a subscription that receives everything published to it. */
    public record Channel(Publisher publisher, Subscription subscription) {
    }

    private String name;

    /** A new topic and subscription named after {@code name}; with {@code claimCheck}, both use it. */
    protected abstract Channel channel(String name, ClaimCheck claimCheck);

    /**
     * A channel whose topic supports ordering keys and deduplication (an SNS FIFO topic with an SQS FIFO
     * queue), or null when the provider has none; ordering and deduplication tests then use {@link #channel}
     * if its publisher declares the capability, and are skipped otherwise.
     */
    protected Channel orderedChannel(String name) {
        return null;
    }

    /** A blob store for claim-check tests, or null to skip them. */
    protected BlobStore claimCheckStore() {
        return null;
    }

    /** Tests to skip because the environment does not implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newName(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        name = "tck-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Channel channel() {
        return channel(name, null);
    }

    private Channel channelWith(MessagingCapability capability) {
        Channel ordered = orderedChannel(name + "-o");
        if (ordered != null && ordered.publisher().capabilities().supports(capability)) {
            return ordered;
        }
        Channel plain = channel();
        assumeTrue(plain.publisher().capabilities().supports(capability),
                () -> plain.publisher().provider() + " does not support " + capability);
        return plain;
    }

    @Test
    void publishedMessageArrivesWithBodyAndAttributes() {
        Channel c = channel();
        String id = c.publisher().publish(OutgoingMessage.builder("hello")
                .attribute("type", "greeting").attribute("tenant", "acme").build()).messageId();

        ReceivedMessage message = receiveOne(c.subscription());

        assertThat(id).isNotBlank();
        assertThat(message.bodyAsString()).isEqualTo("hello");
        assertThat(message.attributes()).containsEntry("type", "greeting").containsEntry("tenant", "acme");
        assertThat(message.deliveryAttempt()).isBetween(0, 1);
        message.ack();
    }

    @Test
    void binaryBodiesArriveUnchanged() {
        Channel c = channel();
        byte[] body = new byte[4096];
        new Random(5).nextBytes(body);
        c.publisher().publish(OutgoingMessage.builder(body).build());

        ReceivedMessage message = receiveOne(c.subscription());

        assertThat(message.body()).isEqualTo(body);
        message.ack();
    }

    @Test
    void ackedMessageIsNotRedelivered() {
        Channel c = channel();
        c.publisher().publish("once");
        receiveOne(c.subscription()).ack();

        assertThat(c.subscription().receive(10, c.subscription().defaultLease().plusSeconds(2))).isEmpty();
    }

    @Test
    void nackedMessageIsRedelivered() {
        Channel c = channel();
        c.publisher().publish("again");
        ReceivedMessage first = receiveOne(c.subscription());
        first.nack();

        ReceivedMessage second = receiveOne(c.subscription());

        assertThat(second.bodyAsString()).isEqualTo("again");
        assertThat(second.id()).isEqualTo(first.id());
        if (first.deliveryAttempt() > 0) {
            assertThat(second.deliveryAttempt()).isGreaterThan(first.deliveryAttempt());
        }
        second.ack();
    }

    @Test
    void unsettledMessageReturnsAfterItsLease() {
        Channel c = channel();
        c.publisher().publish("lease");
        ReceivedMessage first = receiveOne(c.subscription());

        ReceivedMessage again = receiveOne(c.subscription(), c.subscription().defaultLease().multipliedBy(3).plusSeconds(5));

        assertThat(again.id()).isEqualTo(first.id());
        again.ack();
    }

    @Test
    void extendedLeaseKeepsMessageHidden() {
        Channel c = channel();
        c.publisher().publish("extend");
        Duration lease = c.subscription().defaultLease();
        ReceivedMessage message = receiveOne(c.subscription());

        message.extendLease(lease.multipliedBy(4));

        assertThat(c.subscription().receive(10, lease.multipliedBy(2))).isEmpty();
        message.ack();
    }

    @Test
    void publishAllDeliversEveryMessage() {
        Channel c = channel();
        List<OutgoingMessage> batch = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            batch.add(OutgoingMessage.builder("m" + i).attribute("index", String.valueOf(i)).build());
        }

        var results = c.publisher().publishAll(batch);
        Set<String> bodies = drain(c.subscription(), 25, Duration.ofSeconds(60));

        assertThat(results).hasSize(25);
        assertThat(bodies).hasSize(25).contains("m0", "m24");
    }

    @Test
    void listenerAcksSuccessesAndRetriesFailures() throws InterruptedException {
        Channel c = channel();
        Set<String> done = ConcurrentHashMap.newKeySet();
        AtomicInteger flakyFailures = new AtomicInteger();
        CountDownLatch all = new CountDownLatch(5);
        for (int i = 0; i < 4; i++) {
            c.publisher().publish("ok-" + i);
        }
        c.publisher().publish("flaky");

        try (MessageConsumer consumer = c.subscription().listen(message -> {
            if (message.bodyAsString().equals("flaky") && flakyFailures.getAndIncrement() == 0) {
                throw new IllegalStateException("first attempt fails");
            }
            if (done.add(message.bodyAsString())) {
                all.countDown();
            }
        }, quickOptions(4))) {
            assertThat(all.await(90, TimeUnit.SECONDS)).as("all messages handled").isTrue();
            // The ack follows the handler's return; give the last one a moment.
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (consumer.statistics().acked() < 5 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(consumer.statistics().failed()).isGreaterThanOrEqualTo(1);
            assertThat(consumer.statistics().acked()).isGreaterThanOrEqualTo(5);
        }
        assertThat(done).contains("flaky", "ok-0", "ok-3");
        assertThat(c.subscription().receive(10, c.subscription().defaultLease().plusSeconds(2))).isEmpty();
    }

    @Test
    void listenerLimitsConcurrency() throws InterruptedException {
        Channel c = channel();
        List<OutgoingMessage> batch = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            batch.add(OutgoingMessage.of("c" + i));
        }
        c.publisher().publishAll(batch);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        Set<String> done = ConcurrentHashMap.newKeySet();
        CountDownLatch all = new CountDownLatch(12);

        try (MessageConsumer ignored = c.subscription().listen(message -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            Thread.sleep(300);
            running.decrementAndGet();
            if (done.add(message.bodyAsString())) {
                all.countDown();
            }
        }, quickOptions(3))) {
            assertThat(all.await(90, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(peak.get()).isBetween(1, 3);
    }

    @Test
    void claimCheckSendsLargeBodiesThroughBlobStorage() {
        BlobStore store = claimCheckStore();
        assumeTrue(store != null, "no blob store for claim checks");
        Channel c = channel(name + "-cc", new ClaimCheck(store, 64 * 1024, "tck-claims/" + name + "/"));
        byte[] large = new byte[300 * 1024];
        new Random(9).nextBytes(large);

        c.publisher().publish(OutgoingMessage.builder(large).attribute("kind", "large").build());
        c.publisher().publish("small");
        Map<String, ReceivedMessage> received = new LinkedHashMap<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (received.size() < 2 && System.nanoTime() < deadline) {
            for (ReceivedMessage m : c.subscription().receive(10, Duration.ofSeconds(5))) {
                received.put(m.attribute("kind").orElse("small"), m);
                m.ack();
            }
        }

        assertThat(received.get("large").body()).isEqualTo(large);
        assertThat(received.get("large").attributes()).doesNotContainKey(ClaimCheck.ATTRIBUTE);
        assertThat(received.get("small").bodyAsString()).isEqualTo("small");
    }

    @Test
    void orderingKeyDeliversEachKeyInOrder() throws InterruptedException {
        Channel c = channelWith(MessagingCapability.ORDERING);
        // FIFO topics without content-based deduplication need an id on every message.
        boolean dedup = c.publisher().capabilities().supports(MessagingCapability.DEDUPLICATION);
        List<OutgoingMessage> batch = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            for (String key : List.of("a", "b")) {
                var message = OutgoingMessage.builder(key + ":" + i).orderingKey(key);
                if (dedup) {
                    message.deduplicationId(name + key + i);
                }
                batch.add(message.build());
            }
        }
        c.publisher().publishAll(batch);
        Map<String, List<Integer>> seen = new ConcurrentHashMap<>();
        CountDownLatch all = new CountDownLatch(16);

        try (MessageConsumer ignored = c.subscription().listen(message -> {
            String[] parts = message.bodyAsString().split(":");
            List<Integer> forKey = seen.computeIfAbsent(parts[0], k -> Collections.synchronizedList(new ArrayList<>()));
            int index = Integer.parseInt(parts[1]);
            if (!forKey.contains(index)) {
                forKey.add(index);
                all.countDown();
            }
            Thread.sleep(20);
        }, quickOptions(8))) {
            assertThat(all.await(90, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(seen.get("a")).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
        assertThat(seen.get("b")).containsExactly(0, 1, 2, 3, 4, 5, 6, 7);
    }

    @Test
    void repeatedDeduplicationIdIsDeliveredOnce() {
        Channel c = channelWith(MessagingCapability.DEDUPLICATION);
        OutgoingMessage.Builder message = OutgoingMessage.builder("dedup").deduplicationId("order-" + name);
        if (c.publisher().capabilities().supports(MessagingCapability.ORDERING)) {
            message.orderingKey("k"); // FIFO topics need a group
        }
        c.publisher().publish(message.build());
        c.publisher().publish(message.build());

        receiveOne(c.subscription()).ack();

        assertThat(c.subscription().receive(10, Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void delayedMessageIsHeldBack() {
        Channel c = channelWith(MessagingCapability.DELAYED_DELIVERY);
        c.publisher().publish(OutgoingMessage.builder("later").delay(Duration.ofSeconds(4)).build());

        assertThat(c.subscription().receive(10, Duration.ofSeconds(1))).isEmpty();
        ReceivedMessage message = receiveOne(c.subscription(), Duration.ofSeconds(30));
        assertThat(message.bodyAsString()).isEqualTo("later");
        message.ack();
    }

    @Test
    void reservedAttributesAndUnsupportedControlsAreRejected() {
        Channel c = channel();
        assertThatThrownBy(() -> c.publisher().publish(OutgoingMessage.builder("x").attribute("cirrus-x", "1").build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.publisher().publish(OutgoingMessage.builder(new byte[11 * 1024 * 1024]).build()))
                .isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("ClaimCheck");
        assertThatThrownBy(() -> c.subscription().receive(0, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void settlingTwiceIsRejected() {
        Channel c = channel();
        c.publisher().publish("settle");
        ReceivedMessage message = receiveOne(c.subscription());
        message.ack();

        assertThatThrownBy(message::ack).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(message::nack).isInstanceOf(IllegalStateException.class);
    }

    /** Short backoff so retry tests finish quickly. */
    protected ConsumerOptions quickOptions(int concurrency) {
        return ConsumerOptions.DEFAULTS.withConcurrency(concurrency)
                .withPollWait(Duration.ofSeconds(2))
                .withBackoff(Duration.ofMillis(200), Duration.ofSeconds(2));
    }

    protected ReceivedMessage receiveOne(Subscription subscription) {
        return receiveOne(subscription, Duration.ofSeconds(30));
    }

    protected ReceivedMessage receiveOne(Subscription subscription, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long remaining = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(deadline - System.nanoTime()));
            List<ReceivedMessage> messages = subscription.receive(1, Duration.ofSeconds(Math.min(remaining, 10)));
            if (!messages.isEmpty()) {
                return messages.getFirst();
            }
        }
        throw new AssertionError("No message on " + subscription.name() + " within " + timeout);
    }

    /** Receives and acks until {@code count} distinct bodies arrived. */
    private static Set<String> drain(Subscription subscription, int count, Duration timeout) {
        Set<String> bodies = ConcurrentHashMap.newKeySet();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (bodies.size() < count && System.nanoTime() < deadline) {
            for (ReceivedMessage m : subscription.receive(10, Duration.ofSeconds(5))) {
                bodies.add(m.bodyAsString());
                m.ack();
            }
        }
        return bodies;
    }
}
