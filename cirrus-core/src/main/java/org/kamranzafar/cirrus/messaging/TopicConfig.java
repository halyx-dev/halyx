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
package org.kamranzafar.cirrus.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A topic (or queue) to publish to. The name is the provider's: an SNS topic name or ARN (or, with
 * {@code aws.target=sqs}, an SQS queue name or URL), a Service Bus topic or queue, a Pub/Sub topic id.
 * The topic must already exist.
 */
public final class TopicConfig {

    private final String name;
    private final ClaimCheck claimCheck;
    private final Map<String, String> properties;

    private TopicConfig(Builder b) {
        this.name = b.name;
        this.claimCheck = b.claimCheck;
        this.properties = Map.copyOf(b.properties);
    }

    public static TopicConfig of(String name) {
        return builder(name).build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Optional<ClaimCheck> claimCheck() {
        return Optional.ofNullable(claimCheck);
    }

    public Optional<String> property(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    public Map<String, String> properties() {
        return properties;
    }

    @Override
    public String toString() {
        return "TopicConfig[" + name + "]";
    }

    public static final class Builder {
        private final String name;
        private ClaimCheck claimCheck;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Topic name must not be blank");
            }
            this.name = name;
        }

        public Builder claimCheck(ClaimCheck claimCheck) {
            this.claimCheck = Objects.requireNonNull(claimCheck, "claimCheck");
            return this;
        }

        public Builder property(String key, String value) {
            properties.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public TopicConfig build() {
            return new TopicConfig(this);
        }
    }
}
