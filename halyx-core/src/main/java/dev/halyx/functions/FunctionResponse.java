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
import java.util.Optional;

/**
 * What a function returned. A function that failed raises a {@code CloudException} instead, so a response is always
 * a success.
 */
public final class FunctionResponse {

    private final byte[] payload;
    private final String contentType;
    private final Map<String, String> headers;
    private final String executedVersion;

    private FunctionResponse(Builder b) {
        this.payload = b.payload;
        this.contentType = b.contentType;
        this.headers = Map.copyOf(b.headers);
        this.executedVersion = b.executedVersion;
    }

    public static FunctionResponse of(String payload) {
        return builder(payload).build();
    }

    public static FunctionResponse of(byte[] payload) {
        return builder(payload).build();
    }

    public static Builder builder(String payload) {
        return builder(Objects.requireNonNull(payload, "payload").getBytes(StandardCharsets.UTF_8));
    }

    public static Builder builder(byte[] payload) {
        return new Builder(payload);
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

    /** The payload's media type, as the function or cloud reported it; {@code application/json} on Lambda. */
    public String contentType() {
        return contentType;
    }

    /** The response's HTTP headers; empty on Lambda. */
    public Map<String, String> headers() {
        return headers;
    }

    /** The function version that ran, where the cloud reports it (Lambda). */
    public Optional<String> executedVersion() {
        return Optional.ofNullable(executedVersion);
    }

    @Override
    public String toString() {
        return "FunctionResponse[contentType=" + contentType + ", size=" + payload.length + "]";
    }

    public static final class Builder {
        private final byte[] payload;
        private String contentType = FunctionRequest.JSON;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private String executedVersion;

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

        public Builder executedVersion(String executedVersion) {
            this.executedVersion = Objects.requireNonNull(executedVersion, "executedVersion");
            return this;
        }

        public FunctionResponse build() {
            return new FunctionResponse(this);
        }
    }
}
