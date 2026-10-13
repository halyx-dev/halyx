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
package dev.halyx.gcp.pubsub;

import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import dev.halyx.capability.Capabilities;
import dev.halyx.error.CloudException;
import dev.halyx.gcp.GcpErrors;
import dev.halyx.gcp.GcpProvider;
import dev.halyx.messaging.MessagingCapability;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.spi.MessagingErrors;
import dev.halyx.messaging.spi.PublisherDriver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes to a Pub/Sub topic with synchronous batch requests, so messages sharing an ordering key keep
 * their order within and across calls from one publisher. Ordering keys need a subscription with message
 * ordering enabled; Pub/Sub has no publish-time deduplication or delay.
 */
public final class PubSubPublisherDriver implements PublisherDriver {

    private final TopicAdminClient client;
    private final String topic;

    public PubSubPublisherDriver(TopicAdminClient client, String topic) {
        this.client = client;
        this.topic = topic;
    }

    @Override
    public String provider() {
        return GcpProvider.ID;
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public Capabilities capabilities() {
        return Capabilities.of(MessagingCapability.ORDERING);
    }

    /**
     * Pub/Sub requires every message in one publish request to share an ordering key (or have none), so the
     * batch goes out as one request per key, each keeping its messages in order. Ids come back in the
     * caller's order. Not atomic: if a later request fails, earlier keys' messages are already published.
     */
    @Override
    public List<String> publish(List<OutgoingMessage> messages) {
        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            byKey.computeIfAbsent(messages.get(i).orderingKey().orElse(""), k -> new ArrayList<>()).add(i);
        }
        String[] ids = new String[messages.size()];
        for (List<Integer> positions : byKey.values()) {
            List<PubsubMessage> batch = new ArrayList<>(positions.size());
            for (int i : positions) {
                OutgoingMessage message = messages.get(i);
                var builder = PubsubMessage.newBuilder()
                        .setData(ByteString.copyFrom(message.bodyUnsafe()))
                        .putAllAttributes(message.attributes());
                message.orderingKey().ifPresent(builder::setOrderingKey);
                batch.add(builder.build());
            }
            List<String> published = client.publish(topic, batch).getMessageIdsList();
            for (int j = 0; j < positions.size(); j++) {
                ids[positions.get(j)] = published.get(j);
            }
        }
        return List.of(ids);
    }

    @Override
    public int maxBatchSize() {
        return 1000;
    }

    @Override
    public long maxMessageBytes() {
        return 10L * 1000 * 1000;
    }

    @Override
    public CloudException translate(Throwable error) {
        return MessagingErrors.translate(error, GcpProvider.ID, GcpErrors::translate);
    }

    @Override
    public <T> T unwrap(Class<T> nativeType) {
        return MessagingErrors.unwrap(nativeType, GcpProvider.ID, List.of(client));
    }
}
