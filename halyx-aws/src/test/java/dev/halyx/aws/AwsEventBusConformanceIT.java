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

import dev.halyx.Halyx;
import dev.halyx.config.Credentials;
import dev.halyx.config.ProviderConfig;
import dev.halyx.events.CloudEvent;
import dev.halyx.events.EventPublisher;
import dev.halyx.messaging.Subscription;
import dev.halyx.tck.CompletingThreads;
import dev.halyx.tck.EventBusConformanceTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * The event bus kit on EventBridge emulated by Floci: an event bus with a rule that routes the kit's events (their
 * source starts with {@code /halyx-tck/}) to an SQS queue, which the kit receives from.
 */
@Testcontainers(disabledWithoutDocker = true)
class AwsEventBusConformanceIT extends EventBusConformanceTest {

    @Container
    static final GenericContainer<?> FLOCI = new GenericContainer<>(
            DockerImageName.parse(System.getProperty("floci.aws.image", "floci/floci:2.2.0")))
            .withExposedPorts(4566)
            .waitingFor(Wait.forListeningPort());

    private static Halyx halyx;
    private static Subscription inbox;

    @BeforeAll
    static void start() {
        URI endpoint = URI.create("http://" + FLOCI.getHost() + ":" + FLOCI.getMappedPort(4566));
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
        try (SqsClient sqs = SqsClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                     .credentialsProvider(credentials).build();
             EventBridgeClient events = EventBridgeClient.builder().region(Region.US_EAST_1).endpointOverride(endpoint)
                     .credentialsProvider(credentials).build()) {
            String queue = sqs.createQueue(b -> b.queueName("events-inbox")).queueUrl();
            String queueArn = sqs.getQueueAttributes(b -> b.queueUrl(queue).attributeNames(QueueAttributeName.QUEUE_ARN))
                    .attributes().get(QueueAttributeName.QUEUE_ARN);
            events.createEventBus(b -> b.name("halyx-events"));
            events.putRule(b -> b.name("tck").eventBusName("halyx-events")
                    .eventPattern("{\"source\":[{\"prefix\":\"/halyx-tck/\"}]}"));
            events.putTargets(b -> b.rule("tck").eventBusName("halyx-events").targets(t -> t.id("inbox").arn(queueArn)));
        }
        halyx = Halyx.builder()
                .register("aws", ProviderConfig.of("aws").region("us-east-1").endpoint(endpoint)
                        .credentials(Credentials.of("test", "test")))
                .build();
        inbox = halyx.provider("aws").subscription("events-inbox");
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
    }

    @Override
    protected EventPublisher publisher() {
        return halyx.provider("aws").eventPublisher("halyx-events");
    }

    @Override
    protected Subscription inbox() {
        return inbox;
    }

    @Test
    void asyncPublishesCompleteOnTheSdksNonBlockingClient() throws Exception {
        CompletingThreads threads = new CompletingThreads();

        threads.track(() -> publisher().async(threads.executor()).publish(CloudEvent.builder("com.acme.threads", "/halyx-tck/threads").build()))
                .get(30, TimeUnit.SECONDS);

        threads.assertNonBlocking(1, Thread.currentThread());
    }
}
