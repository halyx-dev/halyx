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
package dev.halyx.gcp;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.SubscriptionAdminSettings;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminSettings;
import com.google.pubsub.v1.Subscription;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.messaging.ClaimCheck;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.tck.MessagingConformanceTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;

/** Messaging TCK plumbing shared by the Pub/Sub emulators: a Halyx context plus admin clients to create topics. */
abstract class PubSubConformance extends MessagingConformanceTest {

    static final String PROJECT = "halyx-local";

    /** One emulator's connection; each test class keeps its own. */
    static final class Emulator implements AutoCloseable {
        final Halyx halyx;
        private final ManagedChannel channel;
        private final TopicAdminClient topics;
        private final SubscriptionAdminClient subscriptions;

        Emulator(String host, int port) {
            halyx = Halyx.builder()
                    .register("gcp", ProviderConfig.of("gcp").endpoint(URI.create("http://" + host + ":" + port))
                            .credentials(Credentials.anonymous()).property(GcpProperties.PROJECT_ID, PROJECT))
                    .register("local", ProviderConfig.of("memory"))
                    .build();
            channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
            var transport = FixedTransportChannelProvider.create(GrpcTransportChannel.create(channel));
            try {
                topics = TopicAdminClient.create(TopicAdminSettings.newBuilder().setTransportChannelProvider(transport)
                        .setCredentialsProvider(NoCredentialsProvider.create()).build());
                subscriptions = SubscriptionAdminClient.create(SubscriptionAdminSettings.newBuilder()
                        .setTransportChannelProvider(transport).setCredentialsProvider(NoCredentialsProvider.create()).build());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Channel create(String name, ClaimCheck claimCheck, boolean ordered) {
            String topic = "projects/" + PROJECT + "/topics/" + name;
            topics.createTopic(topic);
            subscriptions.createSubscription(Subscription.newBuilder()
                    .setName("projects/" + PROJECT + "/subscriptions/" + name + "-sub").setTopic(topic)
                    .setAckDeadlineSeconds(10).setEnableMessageOrdering(ordered).build());
            TopicConfig.Builder topicConfig = TopicConfig.builder(name);
            SubscriptionConfig.Builder subscriptionConfig = SubscriptionConfig.builder(name + "-sub");
            if (claimCheck != null) {
                topicConfig.claimCheck(claimCheck);
                subscriptionConfig.claimCheck(claimCheck);
            }
            var gcp = halyx.provider("gcp");
            return new Channel(gcp.publisher(topicConfig.build()), gcp.subscription(subscriptionConfig.build()));
        }

        @Override
        public void close() {
            halyx.close();
            topics.close();
            subscriptions.close();
            channel.shutdownNow();
        }
    }

    abstract Emulator emulator();

    @Override
    protected Channel channel(String name, ClaimCheck claimCheck) {
        return emulator().create(name, claimCheck, false);
    }

    @Override
    protected Channel orderedChannel(String name) {
        return emulator().create(name, null, true);
    }
}
