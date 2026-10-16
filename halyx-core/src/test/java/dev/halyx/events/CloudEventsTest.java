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
package dev.halyx.events;

import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.json.Json;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.ReceivedMessage;
import dev.halyx.messaging.Subscription;
import dev.halyx.messaging.SubscriptionConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudEventsTest {

    private Halyx halyx;

    @BeforeEach
    void start() {
        halyx = Halyx.builder().register("mem", ProviderConfig.of("memory")).build();
    }

    @AfterEach
    void stop() {
        halyx.close();
    }

    /** The message as a queue would deliver it. */
    private ReceivedMessage deliver(OutgoingMessage message) {
        Subscription inbox = halyx.provider("mem").subscription(SubscriptionConfig.builder("inbox").topic("delivered").build());
        halyx.provider("mem").publisher("delivered").publish(message);
        ReceivedMessage received = inbox.receive(1, Duration.ofSeconds(1)).getFirst();
        received.ack();
        return received;
    }

    private static CloudEvent.Builder event() {
        return CloudEvent.builder("com.acme.order.placed", "/orders").id("e-1").time(Instant.parse("2026-10-16T01:02:03Z"));
    }

    @Test
    void jsonDataIsEmbeddedAsJsonAndKeepsItsText() {
        CloudEvent placed = event().subject("o-42").json("{\"total\": 12.50, \"lines\": [1, 2]}")
                .extension("tenant", "acme").build();

        String json = CloudEvents.toJson(placed);

        assertThat(Json.parseObject(json).orElseThrow()).containsEntry("specversion", "1.0")
                .containsEntry("subject", "o-42").containsEntry("tenant", "acme").containsKey("data")
                .doesNotContainKey("data_base64");
        CloudEvent back = CloudEvents.fromJson(json);
        assertThat(back).isEqualTo(placed);
        assertThat(back.dataAsString()).contains("{\"total\": 12.50, \"lines\": [1, 2]}");
    }

    @Test
    void textAndBinaryData() {
        CloudEvent text = event().data("text/plain", "héllo").build();
        CloudEvent binary = event().data("application/octet-stream", new byte[] {0, (byte) 0xff, 7}).build();
        CloudEvent none = event().time(null).build();

        assertThat(Json.parseObject(CloudEvents.toJson(text)).orElseThrow()).containsEntry("data", "héllo");
        assertThat(Json.parseObject(CloudEvents.toJson(binary)).orElseThrow()).containsKey("data_base64");
        for (CloudEvent e : List.of(text, binary, none)) {
            assertThat(CloudEvents.fromJson(CloudEvents.toJson(e))).isEqualTo(e);
        }
        assertThat(none.time()).isEmpty();
        assertThat(none.data()).isEmpty();
    }

    @Test
    void decodesCloudEventsJson() {
        CloudEvent placed = event().json("{\"a\":1}").build();

        assertThat(CloudEvents.decode(deliver(OutgoingMessage.of(CloudEvents.toJson(placed))))).isEqualTo(placed);
    }

    @Test
    void decodesTheCloudEventHalyxPublishedThroughEventBridge() {
        CloudEvent placed = event().json("{\"total\":12.5}").extension("tenant", "acme").build();
        String envelope = "{\"version\":\"0\",\"id\":\"eb-9\",\"detail-type\":\"com.acme.order.placed\",\"source\":\"/orders\","
                + "\"account\":\"123456789012\",\"time\":\"2026-10-16T01:02:03Z\",\"region\":\"ap-southeast-2\",\"resources\":[],"
                + "\"detail\":" + CloudEvents.toJson(placed) + "}";

        assertThat(CloudEvents.decode(deliver(OutgoingMessage.of(envelope)))).isEqualTo(placed);
    }

    @Test
    void decodesOtherEventBridgeEvents() {
        String envelope = "{\"version\":\"0\",\"id\":\"eb-9\",\"detail-type\":\"EC2 Instance State-change Notification\","
                + "\"source\":\"aws.ec2\",\"account\":\"123456789012\",\"time\":\"2026-10-16T01:02:03Z\","
                + "\"region\":\"ap-southeast-2\",\"resources\":[],\"detail\":{\"state\": \"running\"}}";

        CloudEvent event = CloudEvents.decode(deliver(OutgoingMessage.of(envelope)));

        assertThat(event.id()).isEqualTo("eb-9");
        assertThat(event.source()).isEqualTo("aws.ec2");
        assertThat(event.type()).isEqualTo("EC2 Instance State-change Notification");
        assertThat(event.time()).contains(Instant.parse("2026-10-16T01:02:03Z"));
        assertThat(event.dataAsString()).contains("{\"state\": \"running\"}");
        assertThat(event.extensions()).containsEntry("account", "123456789012").containsEntry("region", "ap-southeast-2");
    }

    @Test
    void decodesPubSubBinaryMode() {
        OutgoingMessage message = OutgoingMessage.builder("{\"total\":12.5}".getBytes(StandardCharsets.UTF_8))
                .attribute("ce-specversion", "1.0").attribute("ce-id", "e-1").attribute("ce-source", "/orders")
                .attribute("ce-type", "com.acme.order.placed").attribute("ce-time", "2026-10-16T01:02:03+00:00")
                .attribute("ce-subject", "o-42").attribute("ce-tenant", "acme")
                .attribute("content-type", "application/json").build();

        CloudEvent event = CloudEvents.decode(deliver(message));

        assertThat(event).isEqualTo(event().subject("o-42").json("{\"total\":12.5}").extension("tenant", "acme").build());
    }

    @Test
    void messagesThatHoldNoCloudEventAreRefused() {
        assertThatThrownBy(() -> CloudEvents.decode(deliver(OutgoingMessage.of("plain text"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not a CloudEvent");
        assertThatThrownBy(() -> CloudEvents.decode(deliver(OutgoingMessage.of("{\"hello\":1}"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CloudEvents.fromJson("{\"specversion\":\"0.3\",\"id\":\"1\",\"source\":\"s\",\"type\":\"t\"}"))
                .hasMessageContaining("specversion 0.3");
    }

    @Test
    void extensionNamesFollowTheSpec() {
        assertThatThrownBy(() -> event().extension("Tenant", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event().extension("data", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(event().extension("traceparent1", "x").build().extension("traceparent1")).contains("x");
    }

    @Test
    void rawMembersSurviveNestingEscapesAndSpace() {
        String json = " { \"a\" : \"x}\\\"y\" , \"data\" : { \"n\" : [1, {\"m\": \"]\"}] , \"z\": 1.50 } , \"b\": true } ";

        assertThat(JsonMembers.raw(json, "data")).contains("{ \"n\" : [1, {\"m\": \"]\"}] , \"z\": 1.50 }");
        assertThat(JsonMembers.raw(json, "b")).contains("true");
        assertThat(JsonMembers.raw(json, "missing")).isEmpty();
    }

    @Test
    void theInMemoryBusDeliversToSubscriptionsOnItsTopic() {
        Subscription orders = halyx.provider("mem").subscription(SubscriptionConfig.builder("billing").topic("orders").build());
        CloudEvent placed = event().json("{\"total\":12.5}").build();

        halyx.provider("mem").eventPublisher("orders").publishAll(List.of(placed, event().id("e-2").build()));

        List<ReceivedMessage> received = orders.receive(10, Duration.ofSeconds(1));
        assertThat(received).extracting(m -> CloudEvents.decode(m).id()).containsExactlyInAnyOrder("e-1", "e-2");
        assertThat(received.stream().map(CloudEvents::decode)).contains(placed);
    }
}
