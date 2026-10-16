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
package dev.halyx.aws;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import dev.halyx.Halyx;
import dev.halyx.CloudContext;
import dev.halyx.config.ProviderConfig;
import dev.halyx.messaging.ClaimCheck;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.storage.BlobStore;
import dev.halyx.tck.BlobStoreConformanceTest;
import dev.halyx.tck.DocumentStoreConformanceTest;
import dev.halyx.tck.MessagingConformanceTest;
import dev.halyx.tck.ParameterStoreConformanceTest;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import dev.halyx.tck.DocumentRepositoryConformanceTest;

/**
 * The conformance kits against real AWS (tier 1: S3, DynamoDB, SNS, SQS, SSM, Secrets Manager). Runs only with
 * {@code -Dhalyx.real.aws=true}, normally through scripts/aws-real-cloud.sh, which creates the bucket and
 * tables with Terraform and passes their names in. Credentials and region come from the default AWS chain
 * (AWS_PROFILE, AWS_REGION). Topics and queues are created here, named after the run, and deleted at the end.
 */
@EnabledIfSystemProperty(named = "halyx.real.aws", matches = "true")
class RealAwsIT {

    private static final String RUN = System.getProperty("halyx.real.aws.run", "local");
    private static final String BUCKET = System.getProperty("halyx.real.aws.bucket");
    private static final String ITEMS = System.getProperty("halyx.real.aws.items-table");
    private static final String ITEMS_SORTED = System.getProperty("halyx.real.aws.items-sorted-table");

    private static Halyx halyx;
    private static SnsClient sns;
    private static SqsClient sqs;
    private static final List<String> topics = new CopyOnWriteArrayList<>();
    private static final List<String> queues = new CopyOnWriteArrayList<>();
    private static final AtomicInteger counter = new AtomicInteger();

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("aws", ProviderConfig.of("aws")
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
        if (halyx != null) {
            halyx.close();
        }
        if (sns != null) {
            sns.close();
            sqs.close();
        }
    }

    private static CloudContext aws() {
        return halyx.provider("aws");
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
    class DynamoDbRepositories extends DocumentRepositoryConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            TableConfig.Builder table = TableConfig.builder(sorted ? ITEMS_SORTED : ITEMS);
            if (sorted) {
                table.sortKey("sk");
            }
            return aws().documentStore(DocumentStoreConformanceTest.indexes(table, sorted).build());
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

        /** AWS documents ListSecrets as eventually consistent. */
        @Override
        protected Duration listingLag() {
            return Duration.ofMinutes(2);
        }

        @Override
        protected Duration deleteLag() {
            return Duration.ofSeconds(30);
        }
    }

    /** A queue with a short visibility timeout, named after the run; deleted at the end. */
    static String createQueue(String name, boolean fifo) {
        Map<QueueAttributeName, String> attributes = new HashMap<>();
        attributes.put(QueueAttributeName.VISIBILITY_TIMEOUT, "2");
        if (fifo) {
            attributes.put(QueueAttributeName.FIFO_QUEUE, "true");
        }
        String url = sqs.createQueue(b -> b.queueName("halyx-" + RUN + "-" + name + (fifo ? ".fifo" : ""))
                .attributes(attributes).tags(Map.of("halyx-test", RUN))).queueUrl();
        queues.add(url);
        return url;
    }

    /** A topic delivering to a new queue. Real SNS needs the queue's policy to allow it. */
    static String[] createTopicWithQueue(String name, boolean fifo) {
        String topicArn = sns.createTopic(b -> {
            b.name("halyx-" + RUN + "-" + name + (fifo ? ".fifo" : "")).tags(t -> t.key("halyx-test").value(RUN));
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
