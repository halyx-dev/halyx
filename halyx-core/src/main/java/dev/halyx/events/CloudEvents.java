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

import dev.halyx.json.Json;
import dev.halyx.messaging.ReceivedMessage;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * CloudEvents in their JSON format, and the events inside what each cloud's router delivers to a queue:
 *
 * <ul>
 *   <li>CloudEvents JSON ({@code application/cloudevents+json}): Event Grid namespace subscriptions, and the
 *       in-memory provider</li>
 *   <li>an EventBridge event, as a rule delivers it to SQS: the CloudEvent Halyx published is its {@code detail};
 *       other events become CloudEvents with the envelope's {@code id}, {@code source}, {@code detail-type} as
 *       the type, {@code time}, and {@code detail} as JSON data</li>
 *   <li>a Pub/Sub message in binary mode, as an Eventarc pipeline delivers it: {@code ce-} attributes and the
 *       data as the body</li>
 * </ul>
 *
 * JSON data is kept as the router delivered it; a router may reformat it (EventBridge can change whitespace and
 * number formatting), so compare JSON data as JSON.
 */
public final class CloudEvents {

    /** The media type of one event in the JSON format. */
    public static final String JSON_CONTENT_TYPE = "application/cloudevents+json";

    private static final List<String> BINARY_PREFIXES = List.of("ce-", "ce_");

    private CloudEvents() {
    }

    /** The event in the CloudEvents JSON format; JSON data is embedded as JSON, other data as text or base64. */
    public static String toJson(CloudEvent event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("specversion", CloudEvent.SPEC_VERSION);
        json.put("id", event.id());
        json.put("source", event.source());
        json.put("type", event.type());
        event.subject().ifPresent(v -> json.put("subject", v));
        event.time().ifPresent(v -> json.put("time", v.toString()));
        event.dataContentType().ifPresent(v -> json.put("datacontenttype", v));
        event.dataSchema().ifPresent(v -> json.put("dataschema", v));
        json.putAll(event.extensions());
        byte[] data = event.dataUnsafe();
        if (data == null) {
            return Json.write(json);
        }
        String contentType = event.dataContentType().orElse("");
        String text = new String(data, StandardCharsets.UTF_8);
        if (isJson(contentType) && isValidJson(text)) {
            String attributes = Json.write(json);
            return attributes.substring(0, attributes.length() - 1) + ",\"data\":" + text.strip() + "}";
        }
        if (isText(contentType) && Arrays.equals(text.getBytes(StandardCharsets.UTF_8), data)) { // valid UTF-8
            json.put("data", text);
        } else {
            json.put("data_base64", Base64.getEncoder().encodeToString(data));
        }
        return Json.write(json);
    }

    /** An event from the CloudEvents JSON format. */
    public static CloudEvent fromJson(String json) {
        Map<String, Object> object = Json.parseObject(json).orElseThrow(() ->
                new IllegalArgumentException("Not a CloudEvent: not a JSON object"));
        Object version = object.get("specversion");
        if (!CloudEvent.SPEC_VERSION.equals(version)) {
            throw new IllegalArgumentException("Not a CloudEvents " + CloudEvent.SPEC_VERSION + " event: specversion "
                    + version);
        }
        CloudEvent.Builder event = CloudEvent.builder(string(object, "type"), string(object, "source"))
                .id(string(object, "id"))
                .time(optional(object, "time").map(CloudEvents::instant).orElse(null));
        optional(object, "subject").ifPresent(event::subject);
        optional(object, "dataschema").ifPresent(event::dataSchema);
        String contentType = optional(object, "datacontenttype").orElse(null);
        if (object.containsKey("data_base64")) {
            byte[] data = Base64.getDecoder().decode(string(object, "data_base64"));
            event.data(contentType == null ? "application/octet-stream" : contentType, data);
        } else if (object.containsKey("data")) {
            Object data = object.get("data");
            String type = contentType == null ? (data instanceof String ? "text/plain" : "application/json") : contentType;
            if (data instanceof String s && !isJson(type)) {
                event.data(type, s);
            } else {
                event.data(type, JsonMembers.raw(json, "data").orElseGet(() -> Json.write(data)));
            }
        }
        object.forEach((name, value) -> {
            if (!CloudEvent.ATTRIBUTES.contains(name) && value != null && !(value instanceof Map) && !(value instanceof List)) {
                event.extension(name, value.toString());
            }
        });
        return event.build();
    }

    /**
     * The event in a message a router delivered: CloudEvents JSON, an EventBridge event, or a Pub/Sub message in
     * binary mode. Throws {@code IllegalArgumentException} if the message holds none of these.
     */
    public static CloudEvent decode(ReceivedMessage message) {
        Map<String, String> attributes = message.attributes();
        for (String prefix : BINARY_PREFIXES) {
            if (attributes.containsKey(prefix + "specversion")) {
                return binary(attributes, prefix, message.body());
            }
        }
        String body = message.bodyAsString();
        Map<String, Object> object = Json.parseObject(body).orElseThrow(() -> new IllegalArgumentException(
                "Not a CloudEvent: message " + message.id() + " has no ce- attributes and isn't a JSON object"));
        if (object.containsKey("specversion")) {
            return fromJson(body);
        }
        if (object.containsKey("detail-type") && object.containsKey("detail")) {
            return eventBridge(body, object);
        }
        throw new IllegalArgumentException("Not a CloudEvent: message " + message.id()
                + " is neither CloudEvents JSON, an EventBridge event, nor a message with ce- attributes");
    }

    /** True for {@code application/json}, {@code text/json} and any {@code +json} type. */
    static boolean isJson(String contentType) {
        String type = mediaType(contentType);
        return type.equals("application/json") || type.equals("text/json") || type.endsWith("+json");
    }

    private static boolean isText(String contentType) {
        String type = mediaType(contentType);
        return type.startsWith("text/") || type.endsWith("+xml") || type.equals("application/xml");
    }

    private static String mediaType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidJson(String text) {
        try {
            Json.parse(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static CloudEvent binary(Map<String, String> attributes, String prefix, byte[] body) {
        CloudEvent.Builder event = CloudEvent.builder(required(attributes, prefix + "type"),
                        required(attributes, prefix + "source"))
                .id(required(attributes, prefix + "id"))
                .time(Optional.ofNullable(attributes.get(prefix + "time")).map(CloudEvents::instant).orElse(null));
        Optional.ofNullable(attributes.get(prefix + "subject")).ifPresent(event::subject);
        Optional.ofNullable(attributes.get(prefix + "dataschema")).ifPresent(event::dataSchema);
        String contentType = Optional.ofNullable(attributes.get(prefix + "datacontenttype"))
                .or(() -> Optional.ofNullable(attributes.get("content-type")))
                .orElse("application/octet-stream");
        if (body.length > 0) {
            event.data(contentType, body);
        }
        attributes.forEach((name, value) -> {
            if (name.startsWith(prefix)) {
                String attribute = name.substring(prefix.length());
                if (!CloudEvent.ATTRIBUTES.contains(attribute) && attribute.matches("[a-z0-9]{1,20}")) {
                    event.extension(attribute, value);
                }
            }
        });
        return event.build();
    }

    private static CloudEvent eventBridge(String body, Map<String, Object> envelope) {
        Object detail = envelope.get("detail");
        String rawDetail = JsonMembers.raw(body, "detail").orElseGet(() -> Json.write(detail));
        if (detail instanceof Map<?, ?> map && map.containsKey("specversion")) {
            return fromJson(rawDetail); // published by Halyx: the whole CloudEvent travels as the detail
        }
        CloudEvent.Builder event = CloudEvent.builder(string(envelope, "detail-type"), string(envelope, "source"))
                .id(string(envelope, "id"))
                .time(optional(envelope, "time").map(CloudEvents::instant).orElse(null))
                .json(rawDetail);
        optional(envelope, "account").ifPresent(v -> event.extension("account", v));
        optional(envelope, "region").ifPresent(v -> event.extension("region", v));
        return event.build();
    }

    private static Instant instant(String text) {
        return OffsetDateTime.parse(text).toInstant();
    }

    private static String string(Map<String, Object> object, String name) {
        return optional(object, name).orElseThrow(() -> new IllegalArgumentException("Not a CloudEvent: no " + name));
    }

    private static Optional<String> optional(Map<String, Object> object, String name) {
        Object value = object.get(name);
        return value == null ? Optional.empty() : Optional.of(value.toString());
    }

    private static String required(Map<String, String> attributes, String name) {
        String value = attributes.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Not a CloudEvent: no " + name + " attribute");
        }
        return value;
    }
}
