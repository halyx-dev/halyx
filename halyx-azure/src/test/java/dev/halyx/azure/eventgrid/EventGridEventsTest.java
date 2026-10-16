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
package dev.halyx.azure.eventgrid;

import com.azure.core.util.BinaryData;
import dev.halyx.events.CloudEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventGridEventsTest {

    private static CloudEvent.Builder event() {
        return CloudEvent.builder("com.acme.order.placed", "/orders").id("e-1").time(Instant.parse("2026-10-16T01:02:03Z"));
    }

    @Test
    void roundTripsThroughTheAzureSdksCloudEvent() {
        List<CloudEvent> events = List.of(
                event().subject("o-42").dataSchema("https://acme.example/s").json("{\"total\":12.5}")
                        .extension("tenant", "acme").build(),
                event().data("text/plain", "plain text").build(),
                event().data("application/octet-stream", new byte[] {0, (byte) 0xff, 7}).build(),
                event().time(null).build());

        for (CloudEvent event : events) {
            CloudEvent back = EventGridEvents.fromAzure(EventGridEvents.toAzure(event));
            assertThat(back.time()).as("time").isEqualTo(event.time());
            assertThat(back.dataContentType()).as("content type").isEqualTo(event.dataContentType());
            assertThat(back.data().map(java.util.Arrays::toString)).as("data").isEqualTo(event.data().map(java.util.Arrays::toString));
            assertThat(back.extensions()).as("extensions").isEqualTo(event.extensions());
            assertThat(back).isEqualTo(event);
        }
    }

    @Test
    void jsonDataTravelsAsJson() {
        var azure = EventGridEvents.toAzure(event().json("{\"total\":12.5}").build());

        assertThat(azure.getData().toString()).isEqualTo("{\"total\":12.5}");
        assertThat(azure.getDataContentType()).isEqualTo("application/json");
    }

    /** Event Grid refuses {@code "data": null}, which the SDK writes for absent data. */
    @Test
    void anEventWithoutDataIsSentWithoutNullData() {
        String json = BinaryData.fromObject(EventGridEvents.toAzure(event().build())).toString();

        assertThat(json).doesNotContain("\"data\":null").contains("\"data_base64\":\"\"");
    }
}
