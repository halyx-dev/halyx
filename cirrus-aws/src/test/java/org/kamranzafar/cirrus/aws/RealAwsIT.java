package org.kamranzafar.cirrus.aws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.kamranzafar.cirrus.Cirrus;
import org.kamranzafar.cirrus.CloudContext;
import org.kamranzafar.cirrus.config.ProviderConfig;
import org.kamranzafar.cirrus.messaging.ClaimCheck;
import org.kamranzafar.cirrus.messaging.SubscriptionConfig;
import org.kamranzafar.cirrus.messaging.TopicConfig;
import org.kamranzafar.cirrus.nosql.DocumentStore;
import org.kamranzafar.cirrus.nosql.TableConfig;
import org.kamranzafar.cirrus.parameters.ParameterStoreConfig;
import org.kamranzafar.cirrus.storage.BlobStore;
import org.kamranzafar.cirrus.tck.BlobStoreConformanceTest;
import org.kamranzafar.cirrus.tck.DocumentStoreConformanceTest;
import org.kamranzafar.cirrus.tck.MessagingConformanceTest;
import org.kamranzafar.cirrus.tck.ParameterStoreConformanceTest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The conformance kits against real AWS (tier 1: S3, DynamoDB, SNS, SQS, SSM, Secrets Manager). Runs only with
 * {@code -Dcirrus.real.aws=true}, normally through scripts/aws-real-cloud.sh, which creates the bucket and
 * tables with Terraform and passes their names in. Credentials and region come from the default AWS chain
 * (AWS_PROFILE, AWS_REGION). Topics and queues are created here, named after the run, and deleted at the end.
 */
@EnabledIfSystemProperty(named = "cirrus.real.aws", matches = "true")
class RealAwsIT {

    private static final String RUN = System.getProperty("cirrus.real.aws.run", "local");
    private static final String BUCKET = System.getProperty("cirrus.real.aws.bucket");
    private static final String ITEMS = System.getProperty("cirrus.real.aws.items-table");
    private static final String ITEMS_SORTED = System.getProperty("cirrus.real.aws.items-sorted-table");

    private static Cirrus cirrus;
    private static SnsClient sns;
    private static SqsClient sqs;
    private static final List<String> topics = new CopyOnWriteArrayList<>();
    private static final List<String> queues = new CopyOnWriteArrayList<>();
    private static final AtomicInteger counter = new AtomicInteger();

    @BeforeAll
    static void start() {
        cirrus = Cirrus.builder().register("aws", ProviderConfig.of("aws")
                .region(System.getenv().getOrDefault("AWS_REGION", "ap-southeast-2"))).build();
        Region region = Region.of(System.getenv().getOrDefault("AWS_REGION", "ap-southeast-2"));
        sns = SnsClient.builder().region(region).build();
        sqs = SqsClient.builder().region(region).build();
    }

    @AfterAll
    static void stop() {
        // Everything this run created that Terraform doesn't own.
        for (String queue : queues) {
            try {
                sqs.deleteQueue(b -> b.queueUrl(queue));
            } catch (RuntimeException e) {
                System.err.println("Could not delete queue " + queue + ": " + e.getMessage());
            }
        }
        for (String topic : topics) {
            try {
                sns.deleteTopic(b -> b.topicArn(topic));
            } catch (RuntimeException e) {
                System.err.println("Could not delete topic " + topic + ": " + e.getMessage());
            }
        }
        if (cirrus != null) {
            cirrus.close();
        }
        if (sns != null) {
            sns.close();
            sqs.close();
        }
    }

    private static CloudContext aws() {
        return cirrus.provider("aws");
    }

    @Nested
    class S3 extends BlobStoreConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return aws().blobStore(BUCKET);
        }
    }

    @Nested
    class DynamoDb extends DocumentStoreConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            TableConfig.Builder table = TableConfig.builder(sorted ? ITEMS_SORTED : ITEMS);
            if (sorted) {
                table.sortKey("sk");
            }
            return aws().documentStore(indexes(table, sorted).build());
        }
    }

    @Nested
    class SsmParameterStore extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected String nativePrefix(String unique) {
            return "/" + unique + "/";
        }
    }

    @Nested
    class SecretsManager extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return aws();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }
    }

    /** A queue with a short visibility timeout, named after the run; deleted at the end. */
    static String createQueue(String name, boolean fifo) {
        Map<QueueAttributeName, String> attributes = new HashMap<>();
        attributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, "2");
        if (fifo) {
            attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        }
        String url = sqs.createQueue(b -> b.queueName("cirrus-" + RUN + "-" + name + (fifo ? ".fifo" : ""))
                .attributes(attributes).tags(Map.of("cirrus-test", RUN))).queueUrl();
        queues.add(url);
        return url;
    }

    /** A topic delivering to a new queue. Real SNS needs the queue's policy to allow it. */
    static String[] createTopicWithQueue(String name, boolean fifo) {
        String topicArn = sns.createTopic(b -> {
            b.name("cirrus-" + RUN + "-" + name + (fifo ? ".fifo" : "")).tags(t -> t.key("cirrus-test").value(RUN));
            if (fifo) {
                b.attributes(Map.of("FifoTopic", "true"));
            }
        }).topicArn();
        topics.add(topicArn);
        String queueUrl = createQueue(name + "-q", fifo);
        String queueArn = sqs.getQueueAttributes(b -> b.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .attributes().get(QueueAttributeName.QUEUE_ARN);
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},
                 "Action":"sqs:SendMessage","Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}
                """.formatted(queueArn, topicArn);
        sqs.setQueueAttributes(b -> b.queueUrl(queueUrl).attributes(Map.of(QueueAttributeName.POLICY, policy)));
        sns.subscribe(b -> b.topicArn(topicArn).protocol("sqs").endpoint(queueArn)
                .attributes(Map.of("RawMessageDelivery", "true")));
        try {
            Thread.sleep(3_000); // a new subscription can take a moment to start delivering
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return new String[] {topicArn, queueUrl};
    }

    private static String unique() {
        return "t" + counter.incrementAndGet();
    }

    @Nested
    class SnsToSqs extends MessagingConformanceTest {
        @Override
        protected Channel channel(String name, ClaimCheck claimCheck) {
            return channel(claimCheck, false);
        }

        private Channel channel(ClaimCheck claimCheck, boolean fifo) {
            String[] created = createTopicWithQueue(unique(), fifo);
            TopicConfig.Builder topic = TopicConfig.builder(created[0]);
            SubscriptionConfig.Builder subscription = SubscriptionConfig.builder(created[1]);
            if (claimCheck != null) {
                topic.claimCheck(claimCheck);
                subscription.claimCheck(claimCheck);
            }
            return new Channel(aws().publisher(topic.build()), aws().subscription(subscription.build()));
        }

        @Override
        protected Channel orderedChannel(String name) {
            return channel(null, true);
        }

        @Override
        protected BlobStore claimCheckStore() {
            return aws().blobStore(BUCKET);
        }
    }

    @Nested
    class SqsQueue extends MessagingConformanceTest {
        @Override
        protected Channel channel(String name, ClaimCheck claimCheck) {
            return channel(claimCheck, false);
        }

        private Channel channel(ClaimCheck claimCheck, boolean fifo) {
            String queueUrl = createQueue(unique(), fifo);
            TopicConfig.Builder topic = TopicConfig.builder(queueUrl).property(AwsProperties.MESSAGING_TARGET, "sqs");
            SubscriptionConfig.Builder subscription = SubscriptionConfig.builder(queueUrl);
            if (claimCheck != null) {
                topic.claimCheck(claimCheck);
                subscription.claimCheck(claimCheck);
            }
            return new Channel(aws().publisher(topic.build()), aws().subscription(subscription.build()));
        }

        @Override
        protected Channel orderedChannel(String name) {
            return channel(null, true);
        }

        @Override
        protected BlobStore claimCheckStore() {
            return aws().blobStore(BUCKET);
        }
    }
}
