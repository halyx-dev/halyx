package org.kamranzafar.cirrus.tck;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.messaging.ClaimCheck;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.storage.BlobStore;

/** Runs the messaging TCK against the in-memory broker, which also checks the TCK itself. */
class InMemoryMessagingConformanceTest extends MessagingConformanceTest {

    private static Cirrus cirrus;

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder().register("test", ProviderConfig.of("memory")).build();
    }

    @AfterAll
    static void stop() {
        cirrus.close();
    }

    @Override
    protected Channel channel(String name, ClaimCheck claimCheck) {
        TopicConfig.Builder topic = TopicConfig.builder(name);
        SubscriptionConfig.Builder subscription = SubscriptionConfig.builder(name + "-sub").topic(name)
                .property("memory.lease", "PT1S");
        if (claimCheck != null) {
            topic.claimCheck(claimCheck);
            subscription.claimCheck(claimCheck);
        }
        var context = cirrus.provider("test");
        // Subscribe before publishing, as with a real topic.
        var sub = context.subscription(subscription.build());
        return new Channel(context.publisher(topic.build()), sub);
    }

    @Override
    protected BlobStore claimCheckStore() {
        return cirrus.provider("test").blobStore("claims");
    }
}
