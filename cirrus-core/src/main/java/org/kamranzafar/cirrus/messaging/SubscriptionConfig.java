package org.kamranzafar.cirrus.messaging;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A subscription (or queue) to consume from. The name is the provider's: an SQS queue name or URL (the
 * queue an SNS topic delivers to), a Service Bus queue or {@code <topic>/subscriptions/<name>}, a Pub/Sub
 * subscription id. It must already exist; its native dead-letter settings (SQS redrive policy, Service
 * Bus max delivery count, Pub/Sub dead letter policy) decide where repeatedly failing messages go.
 */
public final class SubscriptionConfig {

    private final String name;
    private final String topic;
    private final ClaimCheck claimCheck;
    private final Map<String, String> properties;

    private SubscriptionConfig(Builder b) {
        this.name = b.name;
        this.topic = b.topic;
        this.claimCheck = b.claimCheck;
        this.properties = Map.copyOf(b.properties);
    }

    public static SubscriptionConfig of(String name) {
        return builder(name).build();
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    /** The topic the subscription receives from, for providers that bind subscriptions themselves (memory). */
    public Optional<String> topic() {
        return Optional.ofNullable(topic);
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
        return "SubscriptionConfig[" + name + "]";
    }

    public static final class Builder {
        private final String name;
        private String topic;
        private ClaimCheck claimCheck;
        private final Map<String, String> properties = new LinkedHashMap<>();

        private Builder(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Subscription name must not be blank");
            }
            this.name = name;
        }

        public Builder topic(String topic) {
            this.topic = Objects.requireNonNull(topic, "topic");
            return this;
        }

        /** Must match the publisher's claim check (same store and prefix) to resolve offloaded bodies. */
        public Builder claimCheck(ClaimCheck claimCheck) {
            this.claimCheck = Objects.requireNonNull(claimCheck, "claimCheck");
            return this;
        }

        public Builder property(String key, String value) {
            properties.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public SubscriptionConfig build() {
            return new SubscriptionConfig(this);
        }
    }
}
