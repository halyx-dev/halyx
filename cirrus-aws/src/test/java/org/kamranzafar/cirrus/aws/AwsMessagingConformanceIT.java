package org.kamranzafar.cirrus.aws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.config.Credentials;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.messaging.ClaimCheck;
import org.kamranzafar.cirrus.messaging.OutgoingMessage;
import org.kamranzafar.cirrus.messaging.ReceivedMessage;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.tck.MessagingConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The messaging TCK on SNS and SQS emulated by Floci: SNS topics fanning out to SQS queues, and plain queues. */
@Testcontainers(disabledWithoutDocker = true)
class AwsMessagingConformanceIT {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static Cirrus cirrus;
    private static SnsClient sns;
    private static SqsClient sqs;

    @BeforeAll
    static void start() {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        cirrus = Cirrus.builder()
                .register("aws", ProviderConfig.of("aws").region("us-east-1").endpoint(endpoint)
                        .credentials(Credentials.of("test", "test"))
                        .property(AwsProperties.S3_PATH_STYLE, "true"))
                .build();
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        sns = SnsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint).credentialsProvider(credentials).build();
        sqs = SqsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint).credentialsProvider(credentials).build();
        try (S3Client s3 = S3Client.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                .credentialsProvider(credentials).forcePathStyle(true).build()) {
            s3.createBucket(b -> b.bucket("claims"));
        }
    }

    @AfterAll
    static void stop() {
        if (cirrus != null) {
            cirrus.close();
        }
        if (sns != null) {
            sns.close();
            sqs.close();
        }
    }

    /** A queue with a two-second visibility timeout, so lease tests are quick. */
    static String createQueue(String name, boolean fifo) {
        Map<QueueAttributeName, String> attributes = new HashMap<>();
        attributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, "2");
        if (fifo) {
            attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        }
        return sqs.createQueue(b -> b.queueName(fifo ? name + ".fifo" : name).attributes(attributes)).queueUrl();
    }

    /** A topic with a queue subscribed to it. */
    static String[] createTopicWithQueue(String name, boolean fifo, boolean raw) {
        String topicArn = sns.createTopic(b -> {
            b.name(fifo ? name + ".fifo" : name);
            if (fifo) {
                b.attributes(Map.of("FifoTopic", "true"));
            }
        }).topicArn();
        String queueUrl = createQueue(name + "-q", fifo);
        String queueArn = sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        sns.subscribe(b -> b.topicArn(topicArn).protocol("sqs").endpoint(queueArn)
                .attributes(Map.of("RawMessageDelivery", String.valueOf(raw))));
        return new String[] {topicArn, queueUrl};
    }

    static BlobStore claims() {
        return cirrus.provider("aws").blobStore("claims");
    }

    @Nested
    class SnsToSqs extends MessagingConformanceTest {

        @Override
        protected Channel channel(String name, ClaimCheck claimCheck) {
            return channel(name, claimCheck, false);
        }

        private Channel channel(String name, ClaimCheck claimCheck, boolean fifo) {
            String[] created = createTopicWithQueue(name, fifo, true);
            TopicConfig.Builder topic = TopicConfig.builder(created[0]);
            SubscriptionConfig.Builder subscription = SubscriptionConfig.builder(created[1]);
            if (claimCheck != null) {
                topic.claimCheck(claimCheck);
                subscription.claimCheck(claimCheck);
            }
            var aws = cirrus.provider("aws");
            return new Channel(aws.publisher(topic.build()), aws.subscription(subscription.build()));
        }

        @Override
        protected Channel orderedChannel(String name) {
            return channel(name, null, true);
        }

        @Override
        protected BlobStore claimCheckStore() {
            return claims();
        }
    }

    @Nested
    class SqsQueue extends MessagingConformanceTest {

        @Override
        protected Channel channel(String name, ClaimCheck claimCheck) {
            return channel(name, claimCheck, false);
        }

        private Channel channel(String name, ClaimCheck claimCheck, boolean fifo) {
            String queueUrl = createQueue(name, fifo);
            TopicConfig.Builder topic = TopicConfig.builder(queueUrl).property(AwsProperties.MESSAGING_TARGET, "sqs");
            SubscriptionConfig.Builder subscription = SubscriptionConfig.builder(queueUrl);
            if (claimCheck != null) {
                topic.claimCheck(claimCheck);
                subscription.claimCheck(claimCheck);
            }
            var aws = cirrus.provider("aws");
            return new Channel(aws.publisher(topic.build()), aws.subscription(subscription.build()));
        }

        @Override
        protected Channel orderedChannel(String name) {
            return channel(name, null, true);
        }

        @Override
        protected BlobStore claimCheckStore() {
            return claims();
        }
    }

    @Test
    void snsEnvelopeIsUnwrappedWithoutRawDelivery() {
        String[] created = createTopicWithQueue("envelope", false, false);
        var aws = cirrus.provider("aws");
        var publisher = aws.publisher(created[0].substring(created[0].lastIndexOf(':') + 1)); // by name
        var subscription = aws.subscription("envelope-q");                                  // by name
        byte[] binary = {0, 1, 2, (byte) 0xFF};

        publisher.publish(OutgoingMessage.builder("wrapped").attribute("kind", "text").build());
        publisher.publish(OutgoingMessage.builder(binary).attribute("kind", "binary").build());

        Map<String, ReceivedMessage> byKind = new HashMap<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (byKind.size() < 2 && System.nanoTime() < deadline) {
            List<ReceivedMessage> messages = subscription.receive(10, Duration.ofSeconds(5));
            messages.forEach(m -> {
                byKind.put(m.attribute("kind").orElse("?"), m);
                m.ack();
            });
        }
        assertThat(byKind.get("text").bodyAsString()).isEqualTo("wrapped");
        assertThat(byKind.get("binary").body()).isEqualTo(binary);
        assertThat(byKind.get("binary").attributes()).containsOnlyKeys("kind");
    }
}
