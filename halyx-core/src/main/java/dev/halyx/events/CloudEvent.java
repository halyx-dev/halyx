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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An event in the <a href="https://cloudevents.io">CloudEvents 1.0</a> format, the portable form of what
 * EventBridge, Event Grid and Eventarc carry.
 *
 * <pre>{@code
 * CloudEvent placed = CloudEvent.builder("com.acme.order.placed", "/orders")
 *         .subject("o-42")
 *         .data("application/json", "{\"total\":12.5}")
 *         .extension("tenant", "acme")
 *         .build();
 * }</pre>
 *
 * {@code id} defaults to a random UUID and {@code time} to now. Events compare by value, data by content.
 */
public final class CloudEvent {

    public static final String SPEC_VERSION = "1.0";

    /** The attribute names the spec defines, which extensions can't use. */
    static final Set<String> ATTRIBUTES = Set.of("specversion", "id", "source", "type", "subject", "time",
            "datacontenttype", "dataschema", "data", "data_base64");

    private static final Pattern EXTENSION_NAME = Pattern.compile("[a-z0-9]{1,20}");

    private final String id;
    private final String source;
    private final String type;
    private final String subject;
    private final Instant time;
    private final String dataContentType;
    private final String dataSchema;
    private final byte[] data;
    private final Map<String, String> extensions;

    private CloudEvent(Builder b) {
        this.id = b.id;
        this.source = b.source;
        this.type = b.type;
        this.subject = b.subject;
        this.time = b.time;
        this.dataContentType = b.dataContentType;
        this.dataSchema = b.dataSchema;
        this.data = b.data;
        this.extensions = Collections.unmodifiableMap(new LinkedHashMap<>(b.extensions));
    }

    /**
     * @param type   what happened, reverse-DNS style, such as {@code com.acme.order.placed}
     * @param source where it happened, a URI reference such as {@code /orders} or {@code https://acme.example/orders}
     */
    public static Builder builder(String type, String source) {
        return new Builder(type, source);
    }

    /** Unique within {@link #source()}: retries of one event keep its id, so receivers can deduplicate. */
    public String id() {
        return id;
    }

    public String source() {
        return source;
    }

    public String type() {
        return type;
    }

    public Optional<String> subject() {
        return Optional.ofNullable(subject);
    }

    public Optional<Instant> time() {
        return Optional.ofNullable(time);
    }

    /** The media type of {@link #data()}, such as {@code application/json}. */
    public Optional<String> dataContentType() {
        return Optional.ofNullable(dataContentType);
    }

    public Optional<String> dataSchema() {
        return Optional.ofNullable(dataSchema);
    }

    /** A copy of the data, or empty when the event has none. */
    public Optional<byte[]> data() {
        return Optional.ofNullable(data).map(byte[]::clone);
    }

    /** The data as UTF-8 text, or empty when the event has none. */
    public Optional<String> dataAsString() {
        return Optional.ofNullable(data).map(d -> new String(d, StandardCharsets.UTF_8));
    }

    /** Extension attributes: lower-case letters and digits, up to 20 characters. */
    public Map<String, String> extensions() {
        return extensions;
    }

    public Optional<String> extension(String name) {
        return Optional.ofNullable(extensions.get(name));
    }

    /** Size of the data in bytes, 0 without data. */
    public int dataSize() {
        return data == null ? 0 : data.length;
    }

    byte[] dataUnsafe() {
        return data;
    }

    public Builder toBuilder() {
        Builder b = new Builder(type, source).id(id);
        b.subject = subject;
        b.time = time;
        b.dataContentType = dataContentType;
        b.dataSchema = dataSchema;
        b.data = data;
        b.extensions.putAll(extensions);
        return b;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CloudEvent e && id.equals(e.id) && source.equals(e.source) && type.equals(e.type)
                && Objects.equals(subject, e.subject) && Objects.equals(time, e.time)
                && Objects.equals(dataContentType, e.dataContentType) && Objects.equals(dataSchema, e.dataSchema)
                && Arrays.equals(data, e.data) && extensions.equals(e.extensions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, source, type, subject, time, dataContentType, dataSchema, Arrays.hashCode(data), extensions);
    }

    @Override
    public String toString() {
        return "CloudEvent[type=" + type + ", source=" + source + ", id=" + id
                + (subject == null ? "" : ", subject=" + subject) + ", data=" + dataSize() + " bytes]";
    }

    public static final class Builder {
        private String id = UUID.randomUUID().toString();
        private final String source;
        private final String type;
        private String subject;
        private Instant time = Instant.now();
        private String dataContentType;
        private String dataSchema;
        private byte[] data;
        private final Map<String, String> extensions = new LinkedHashMap<>();

        private Builder(String type, String source) {
            this.type = requireText(type, "type");
            this.source = requireText(source, "source");
        }

        public Builder id(String id) {
            this.id = requireText(id, "id");
            return this;
        }

        public Builder subject(String subject) {
            this.subject = requireText(subject, "subject");
            return this;
        }

        /** When it happened; null leaves it out. Defaults to now. */
        public Builder time(Instant time) {
            this.time = time;
            return this;
        }

        public Builder dataSchema(String dataSchema) {
            this.dataSchema = requireText(dataSchema, "dataSchema");
            return this;
        }

        /** Data with its media type, such as {@code application/json} or {@code application/octet-stream}. */
        public Builder data(String contentType, byte[] data) {
            this.dataContentType = requireText(contentType, "contentType");
            this.data = Objects.requireNonNull(data, "data").clone();
            return this;
        }

        /** Text data, stored as UTF-8. */
        public Builder data(String contentType, String data) {
            return data(contentType, Objects.requireNonNull(data, "data").getBytes(StandardCharsets.UTF_8));
        }

        /** JSON data ({@code application/json}). */
        public Builder json(String json) {
            return data("application/json", json);
        }

        public Builder extension(String name, String value) {
            Objects.requireNonNull(name, "name");
            if (!EXTENSION_NAME.matcher(name).matches() || ATTRIBUTES.contains(name)) {
                throw new IllegalArgumentException("Extension names are 1-20 lower-case letters and digits, and not a "
                        + "CloudEvents attribute: " + name);
            }
            extensions.put(name, Objects.requireNonNull(value, "value"));
            return this;
        }

        public CloudEvent build() {
            return new CloudEvent(this);
        }

        private static String requireText(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be empty");
            }
            return value;
        }
    }
}
