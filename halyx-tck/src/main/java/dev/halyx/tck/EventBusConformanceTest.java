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

import dev.halyx.error.InvalidArgumentException;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.CloudEvents;
import dev.halyx.events.EventPublisher;
import dev.halyx.json.Json;
import dev.halyx.messaging.ConsumerOptions;
import dev.halyx.messaging.MessageConsumer;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Conformance suite for event buses: events published to a provider's router arrive, intact, in the queue its rule
 * or subscription delivers to. The queue may be shared by every test, so each test publishes with a
 * {@code source} of its own and takes only its own events; others it receives are acknowledged and ignored.
 * Delivery is at least once, so duplicates are tolerated, and the router may reorder events.
 */
public abstract class EventBusConformanceTest {

    private String source;

    /** The publisher for the bus under test. */
    protected abstract EventPublisher publisher();

    /** The subscription the router delivers the bus's events to. */
    protected abstract Subscription inbox();

    /** How long events may take to arrive; real routers need longer than emulators. */
    protected Duration timeout() {
        return Duration.ofSeconds(30);
    }

    /** Tests to skip because the environment does not implement the behaviour, mapped to the reason. */
    protected Map<String, String> knownEnvironmentGaps() {
        return Map.of();
    }

    @BeforeEach
    void newSource(TestInfo test) {
        test.getTestMethod().map(m -> knownEnvironmentGaps().get(m.getName()))
                .ifPresent(reason -> assumeTrue(false, "Known environment gap: " + reason));
        source = "/halyx-tck/" + UUID.randomUUID().toString().substring(0, 8);
    }

    private CloudEvent.Builder event(String type) {
        return CloudEvent.builder("dev.halyx.tck." + type, source);
    }

    /** At least {@code count} distinct events from this test's source, by id. */
    private Map<String, CloudEvent> awaitEvents(int count) {
        Map<String, CloudEvent> mine = new ConcurrentHashMap<>();
        Instant deadline = Instant.now().plus(timeout());
        while (mine.size() < count && Instant.now().isBefore(deadline)) {
            for (ReceivedMessage message : inbox().receive(10, Duration.ofSeconds(2))) {
                CloudEvent event = CloudEvents.decode(message);
                message.ack();
                if (event.source().equals(source)) {
                    mine.put(event.id(), event);
                }
            }
        }
        assertThat(mine).as("events from " + source).hasSizeGreaterThanOrEqualTo(count);
        return mine;
    }

    @Test
    void publishedEventArrivesWithEveryAttribute() {
        Instant time = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CloudEvent placed = event("order.placed").subject("o-42").time(time).dataSchema("https://acme.example/order.json")
                .json("{\"total\":12.5,\"lines\":[\"a\",\"b\"]}").extension("tenant", "acme").extension("attempt", "1")
                .build();

        publisher().publish(placed);

        CloudEvent received = awaitEvents(1).get(placed.id());
        assertThat(received).isNotNull();
        assertThat(received.type()).isEqualTo(placed.type());
        assertThat(received.source()).isEqualTo(source);
        assertThat(received.subject()).contains("o-42");
        assertThat(received.time()).contains(time);
        assertThat(received.dataSchema()).contains("https://acme.example/order.json");
        assertThat(received.dataContentType()).hasValueSatisfying(t -> assertThat(t).startsWith("application/json"));
        // Routers may reformat JSON data, so compare it as JSON.
        assertThat(Json.parse(received.dataAsString().orElseThrow())).isEqualTo(Json.parse(placed.dataAsString().orElseThrow()));
        assertThat(received.extensions()).containsEntry("tenant", "acme").containsEntry("attempt", "1");
    }

    @Test
    void textAndBinaryData() {
        byte[] bytes = new byte[512];
        new Random(5).nextBytes(bytes);
        CloudEvent text = event("text").data("text/plain", "plain text, ünïcode").build();
        CloudEvent binary = event("binary").data("application/octet-stream", bytes).build();

        publisher().publishAll(List.of(text, binary));

        Map<String, CloudEvent> received = awaitEvents(2);
        assertThat(received.get(text.id()).dataAsString()).contains("plain text, ünïcode");
        assertThat(received.get(binary.id()).data()).hasValueSatisfying(d -> assertThat(d).isEqualTo(bytes));
    }

    @Test
    void eventsWithoutDataArrive() {
        CloudEvent none = event("none").build();

        publisher().publish(none);

        CloudEvent received = awaitEvents(1).get(none.id());
        assertThat(received.data()).isEmpty();
        assertThat(received.type()).isEqualTo(none.type());
    }

    @Test
    void publishAllDeliversEveryEventAcrossRequests() {
        List<CloudEvent> events = IntStream.range(0, 25)
                .mapToObj(i -> event("batch").subject("item-" + i).json("{\"i\":" + i + "}").build()).toList();

        publisher().publishAll(events);

        assertThat(awaitEvents(25).keySet()).containsAll(events.stream().map(CloudEvent::id).toList());
    }

    @Test
    void aRepublishedEventKeepsItsId() {
        CloudEvent once = event("retry").json("{\"n\":1}").build();

        publisher().publish(once);
        publisher().publish(once);

        Map<String, CloudEvent> received = awaitEvents(1);
        assertThat(received).containsOnlyKeys(once.id());
    }

    @Test
    void oversizedEventsAreRefusedBeforeSending() {
        CloudEvent huge = event("huge").data("application/octet-stream", new byte[2 * 1024 * 1024]).build();

        assertThatThrownBy(() -> publisher().publish(huge)).isInstanceOf(InvalidArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void listenersReceiveEvents() throws InterruptedException {
        List<String> handled = new ArrayList<>();
        CloudEvent shipped = event("order.shipped").json("{\"carrier\":\"acme\"}").build();
        try (MessageConsumer consumer = inbox().listen(message -> {
            CloudEvent event = CloudEvents.decode(message);
            if (event.source().equals(source)) {
                synchronized (handled) {
                    handled.add(event.id());
                }
            }
            message.ack();
        }, ConsumerOptions.DEFAULTS)) {
            publisher().publish(shipped);
            Instant deadline = Instant.now().plus(timeout());
            while (Instant.now().isBefore(deadline)) {
                synchronized (handled) {
                    if (handled.contains(shipped.id())) {
                        break;
                    }
                }
                Thread.sleep(200);
            }
        }
        assertThat(handled).contains(shipped.id());
    }
}
