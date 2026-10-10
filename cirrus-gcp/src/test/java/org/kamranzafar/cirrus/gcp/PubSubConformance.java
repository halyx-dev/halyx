package org.kamranzafar.cirrus.gcp;

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
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.messaging.ClaimCheck;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.tck.MessagingConformanceTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;

/** Messaging TCK plumbing shared by the Pub/Sub emulators: a Cirrus context plus admin clients to create topics. */
abstract class PubSubConformance extends MessagingConformanceTest {

    static final String PROJECT = "cirrus-local";

    /** One emulator's connection; each test class keeps its own. */
    static final class Emulator implements AutoCloseable {
        final Cirrus cirrus;
        private final ManagedChannel channel;
        private final TopicAdminClient topics;
        private final SubscriptionAdminClient subscriptions;

        Emulator(String host, int port) {
            cirrus = Cirrus.builder()
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
            var gcp = cirrus.provider("gcp");
            return new Channel(gcp.publisher(topicConfig.build()), gcp.subscription(subscriptionConfig.build()));
        }

        @Override
        public void close() {
            cirrus.close();
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
