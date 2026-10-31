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
package dev.halyx.functions;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A function invocation: a payload, its content type and string headers. The request's headers are added to the
 * function's configured ones, and replace any with the same name. Halyx adds no headers of its own except the login.
 */
public final class FunctionRequest {

    public static final String JSON = "application/json";

    private final byte[] payload;
    private final String contentType;
    private final Map<String, String> headers;

    private FunctionRequest(Builder b) {
        this.payload = b.payload;
        this.contentType = b.contentType;
        this.headers = Map.copyOf(b.headers);
    }

    public static FunctionRequest of(String payload) {
        return builder(payload).build();
    }

    public static FunctionRequest of(byte[] payload) {
        return builder(payload).build();
    }

    public static Builder builder(String payload) {
        return builder(Objects.requireNonNull(payload, "payload").getBytes(StandardCharsets.UTF_8));
    }

    public static Builder builder(byte[] payload) {
        return new Builder(payload);
    }

    /**
     * A copy with a different payload, the same content type, and these headers on top of its own (used by
     * interceptors and decorators, such as tracing).
     */
    public FunctionRequest withPayload(byte[] newPayload, Map<String, String> extraHeaders) {
        Builder b = new Builder(newPayload);
        b.contentType = contentType;
        b.headers.putAll(headers);
        b.headers(extraHeaders);
        return b.build();
    }

    public byte[] payload() {
        return payload.clone();
    }

    /** The payload without copying; callers must not modify it. */
    public byte[] payloadUnsafe() {
        return payload;
    }

    public String payloadAsString() {
        return new String(payload, StandardCharsets.UTF_8);
    }

    public int size() {
        return payload.length;
    }

    /** The payload's media type: {@code application/json} unless the builder set another. */
    public String contentType() {
        return contentType;
    }

    public Map<String, String> headers() {
        return headers;
    }

    @Override
    public String toString() {
        return "FunctionRequest[contentType=" + contentType + ", size=" + payload.length + "]";
    }

    public static final class Builder {
        private final byte[] payload;
        private String contentType = JSON;
        private final Map<String, String> headers = new LinkedHashMap<>();

        private Builder(byte[] payload) {
            this.payload = Objects.requireNonNull(payload, "payload").clone();
        }

        public Builder contentType(String contentType) {
            if (Objects.requireNonNull(contentType, "contentType").isBlank()) {
                throw new IllegalArgumentException("contentType must not be blank");
            }
            this.contentType = contentType;
            return this;
        }

        /** A string header, sent on top of the function's configured headers and replacing one of the same name. */
        public Builder header(String name, String value) {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(value, "value");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Header names must not be blank");
            }
            headers.put(name, value);
            return this;
        }

        public Builder headers(Map<String, String> entries) {
            entries.forEach(this::header);
            return this;
        }

        public FunctionRequest build() {
            return new FunctionRequest(this);
        }
    }
}
