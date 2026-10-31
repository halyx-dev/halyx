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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A function to invoke: a Lambda function (name or ARN, with an optional {@code aws.lambda.qualifier}), an Azure
 * function (with {@code azure.functions.endpoint}, and {@code azure.functions.audience} or
 * {@code azure.functions.key}), or a Cloud Run function (its name, or {@code gcp.functions.url}). Provider settings go
 * in {@link Builder#property}.
 */
public final class FunctionConfig {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final String name;
    private final Duration timeout;
    private final Map<String, String> headers;
    private final boolean idempotent;
    private final Map<String, String> properties;

    private FunctionConfig(Builder b) {
        this.name = b.name;
        this.timeout = b.timeout;
        this.headers = Map.copyOf(b.headers);
        this.idempotent = b.idempotent;
        this.properties = Map.copyOf(b.properties);
    }

    public static FunctionConfig of(String name) {
        return builder(name).build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    /** How long an invocation may take, from sending the request to reading the response. */
    public Duration timeout() {
        return timeout;
    }

    /** Headers sent on every invocation; a request's own headers replace those with the same name. */
    public Map<String, String> headers() {
        return headers;
    }

    /**
     * Whether running the function twice for one call is harmless. Only idempotent functions are retried after a
     * request was sent, by Halyx' retry interceptor or the cloud's SDK.
     */
    public boolean idempotent() {
        return idempotent;
    }

    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, String> properties() {
        return properties;
    }

    /** Configurations with the same settings are equal, so contexts can share one client per configuration. */
    @Override
    public boolean equals(Object o) {
        return o instanceof FunctionConfig other && Objects.equals(name, other.name)
                && Objects.equals(timeout, other.timeout) && Objects.equals(headers, other.headers)
                && idempotent == other.idempotent && Objects.equals(properties, other.properties);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, timeout, headers, idempotent, properties);
    }

    @Override
    public String toString() {
        return "FunctionConfig[" + name + "]";
    }

    public static final class Builder {
        private final String name;
        private Duration timeout = DEFAULT_TIMEOUT;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private boolean idempotent;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Function name must not be blank");
            }
            this.name = name;
        }

        /** How long an invocation may take; 60 seconds by default. */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /** A header sent on every invocation of the function. */
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

        /** Whether invocations may be retried after the request was sent; false by default. */
        public Builder idempotent(boolean idempotent) {
            this.idempotent = idempotent;
            return this;
        }

        public Builder property(String key, String value) {
            Objects.requireNonNull(key, "key");
            if (Objects.requireNonNull(value, "value").isBlank()) {
                properties.remove(key); // blank is unset, as an empty ${VAR:} in Spring configuration gives
            } else {
                properties.put(key, value);
            }
            return this;
        }

        public FunctionConfig build() {
            return new FunctionConfig(this);
        }
    }
}
