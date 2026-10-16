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

import com.azure.core.models.CloudEventDataFormat;
import com.azure.core.util.BinaryData;
import dev.halyx.events.CloudEvent;

import java.time.ZoneOffset;
import java.util.Map;

/** Converts between Halyx' CloudEvent and the Azure SDK's. */
final class EventGridEvents {

    private EventGridEvents() {
    }

    static com.azure.core.models.CloudEvent toAzure(CloudEvent event) {
        String contentType = event.dataContentType().orElse(null);
        com.azure.core.models.CloudEvent azure;
        if (event.data().isEmpty()) {
            // The SDK writes absent data as "data": null, which Event Grid refuses; empty data_base64 is accepted,
            // and comes back as no data.
            azure = new com.azure.core.models.CloudEvent(event.source(), event.type(), BinaryData.fromBytes(new byte[0]),
                    CloudEventDataFormat.BYTES, null);
        } else if (contentType != null && isJson(contentType)) {
            azure = new com.azure.core.models.CloudEvent(event.source(), event.type(),
                    BinaryData.fromString(event.dataAsString().orElseThrow()), CloudEventDataFormat.JSON, contentType);
        } else {
            azure = new com.azure.core.models.CloudEvent(event.source(), event.type(),
                    BinaryData.fromBytes(event.data().orElseThrow()), CloudEventDataFormat.BYTES, contentType);
        }
        azure.setId(event.id());
        // The SDK stamps new events with the current time; an event without one keeps none.
        azure.setTime(event.time().map(t -> t.atOffset(ZoneOffset.UTC)).orElse(null));
        event.subject().ifPresent(azure::setSubject);
        event.dataSchema().ifPresent(azure::setDataSchema);
        event.extensions().forEach(azure::addExtensionAttribute);
        return azure;
    }

    static CloudEvent fromAzure(com.azure.core.models.CloudEvent azure) {
        CloudEvent.Builder event = CloudEvent.builder(azure.getType(), azure.getSource()).id(azure.getId())
                .time(azure.getTime() == null ? null : azure.getTime().toInstant());
        if (azure.getSubject() != null) {
            event.subject(azure.getSubject());
        }
        if (azure.getDataSchema() != null) {
            event.dataSchema(azure.getDataSchema());
        }
        // The SDK reports an event without data as empty data.
        byte[] data = azure.getData() == null ? new byte[0] : azure.getData().toBytes();
        if (data.length > 0) {
            String contentType = azure.getDataContentType() == null ? "application/json" : azure.getDataContentType();
            event.data(contentType, data);
        }
        Map<String, Object> extensions = azure.getExtensionAttributes();
        if (extensions != null) {
            extensions.forEach((name, value) -> event.extension(name, String.valueOf(value)));
        }
        return event.build();
    }

    private static boolean isJson(String contentType) {
        String type = contentType.split(";", 2)[0].trim().toLowerCase(java.util.Locale.ROOT);
        return type.equals("application/json") || type.equals("text/json") || type.endsWith("+json");
    }
}
