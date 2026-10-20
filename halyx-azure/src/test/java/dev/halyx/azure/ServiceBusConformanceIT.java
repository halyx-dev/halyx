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
package dev.halyx.azure;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.messaging.ClaimCheck;
import dev.halyx.messaging.OutgoingMessage;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.storage.BlobStore;
import dev.halyx.tck.CompletingThreads;
import dev.halyx.tck.MessagingConformanceTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The messaging TCK on Microsoft's Service Bus emulator (with its SQL Edge store). The emulator only serves
 * entities declared in its config, so a pool of topics is declared up front and each test takes the next.
 */
@Testcontainers(disabledWithoutDocker = true)
class ServiceBusConformanceIT extends MessagingConformanceTest {

    private static final int TOPICS = 30;
    private static final int DEDUP_TOPICS = 6;
    private static final String SQL_PASSWORD = "Halyx-Tck-2026!";

    private static final Network NETWORK = Network.newNetwork();

    static final GenericContainer<?> SQL = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("sqledge.image", "mcr.microsoft.com/azure-sql-edge:2.0.0")))
            .withNetwork(NETWORK).withNetworkAliases("sqledge")
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("MSSQL_SA_PASSWORD", SQL_PASSWORD)
            .waitingFor(Wait.forLogMessage(".*SQL Server is now ready for client connections.*", 1))
            .withStartupTimeout(Duration.ofMinutes(3));

    static final GenericContainer<?> EMULATOR = new GenericContainer<>(DockerImageName.parse(
            System.getProperty("servicebus.emulator.image", "mcr.microsoft.com/azure-messaging/servicebus-emulator:2.0.1")))
            .withNetwork(NETWORK)
            .withEnv("ACCEPT_EULA", "Y")
            .withEnv("SQL_SERVER", "sqledge")
            .withEnv("MSSQL_SA_PASSWORD", SQL_PASSWORD)
            .withCopyToContainer(Transferable.of(config()), "/ServiceBus_Emulator/ConfigFiles/Config.json")
            .withExposedPorts(5672)
            .waitingFor(Wait.forLogMessage(".*Emulator Service is Successfully Up!.*", 1))
            .withStartupTimeout(Duration.ofMinutes(4));

    private static final AtomicInteger NEXT_TOPIC = new AtomicInteger();
    private static final AtomicInteger NEXT_DEDUP_TOPIC = new AtomicInteger();
    private static Halyx halyx;

    @BeforeAll
    static void start() {
        SQL.start();
        EMULATOR.dependsOn(SQL).start();
        String connection = "Endpoint=sb://" + EMULATOR.getHost() + ":" + EMULATOR.getMappedPort(5672)
                + ";SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;";
        halyx = Halyx.builder()
                .register("azure", ProviderConfig.of("azure").property(AzureProperties.SERVICE_BUS_CONNECTION_STRING, connection))
                .register("local", ProviderConfig.of("memory"))
                .build();
    }

    @AfterAll
    static void stop() {
        if (halyx != null) {
            halyx.close();
        }
        EMULATOR.stop();
        SQL.stop();
        NETWORK.close();
    }

    /** Topics with a 5-second-lock subscription each, some with duplicate detection, and one queue (the emulator caps MaxDeliveryCount at 10). */
    private static String config() {
        List<String> topics = new ArrayList<>();
        for (int i = 0; i < TOPICS; i++) {
            topics.add(topic("tck-" + i, false, false));
        }
        for (int i = 0; i < DEDUP_TOPICS; i++) {
            topics.add(topic("tck-dedup-" + i, true, true));
        }
        return """
                {"UserConfig":{"Namespaces":[{"Name":"sbemulatorns",
                  "Queues":[{"Name":"tck-queue","Properties":{"DeadLetteringOnMessageExpiration":false,
                    "DefaultMessageTimeToLive":"PT1H","DuplicateDetectionHistoryTimeWindow":"PT20S",
                    "ForwardDeadLetteredMessagesTo":"","ForwardTo":"","LockDuration":"PT5S","MaxDeliveryCount":10,
                    "RequiresDuplicateDetection":false,"RequiresSession":false}}],
                  "Topics":[%s]}],
                  "Logging":{"Type":"File"}}}
                """.formatted(String.join(",", topics));
    }

    private static String topic(String name, boolean dedup, boolean sessions) {
        return """
                {"Name":"%s","Properties":{"DefaultMessageTimeToLive":"PT1H","DuplicateDetectionHistoryTimeWindow":"PT1M",
                  "RequiresDuplicateDetection":%s},
                 "Subscriptions":[{"Name":"sub","Properties":{"DeadLetteringOnMessageExpiration":false,
                   "DefaultMessageTimeToLive":"PT1H","LockDuration":"PT5S","MaxDeliveryCount":10,
                   "ForwardDeadLetteredMessagesTo":"","ForwardTo":"","RequiresSession":%s},"Rules":[]}]}
                """.formatted(name, dedup, sessions);
    }

    private Channel channelOn(String topic, ClaimCheck claimCheck) {
        return channelOn(topic, claimCheck, false);
    }

    private Channel channelOn(String topic, ClaimCheck claimCheck, boolean sessions) {
        TopicConfig.Builder topicConfig = TopicConfig.builder(topic)
                .property(AzureProperties.SERVICE_BUS_SESSIONS, String.valueOf(sessions));
        SubscriptionConfig.Builder subscriptionConfig = SubscriptionConfig.builder(topic + "/subscriptions/sub")
                .property(AzureProperties.SERVICE_BUS_SESSIONS, String.valueOf(sessions));
        if (claimCheck != null) {
            topicConfig.claimCheck(claimCheck);
            subscriptionConfig.claimCheck(claimCheck);
        }
        var azure = halyx.provider("azure");
        return new Channel(azure.publisher(topicConfig.build()), azure.subscription(subscriptionConfig.build()));
    }

    @Override
    protected Channel channel(String name, ClaimCheck claimCheck) {
        int index = NEXT_TOPIC.getAndIncrement();
        if (index >= TOPICS) {
            throw new IllegalStateException("Declare more topics in the emulator config");
        }
        return channelOn("tck-" + index, claimCheck);
    }

    /** Session-enabled topics with duplicate detection: ordering keys, deduplication and delays. */
    @Override
    protected Channel orderedChannel(String name) {
        return channelOn("tck-dedup-" + NEXT_DEDUP_TOPIC.getAndIncrement(), null, true);
    }

    @Override
    protected BlobStore claimCheckStore() {
        return halyx.provider("local").blobStore("claims");
    }

    @Test
    void queuesWorkPointToPoint() {
        var azure = halyx.provider("azure");
        var publisher = azure.publisher(TopicConfig.builder("tck-queue").property(AzureProperties.SERVICE_BUS_ENTITY, "queue").build());
        var subscription = azure.subscription("tck-queue");

        publisher.publish(OutgoingMessage.builder("direct").attribute("via", "queue").build());
        var message = receiveOne(subscription);

        assertThat(message.bodyAsString()).isEqualTo("direct");
        assertThat(message.attributes()).containsEntry("via", "queue");
        assertThat(message.deliveryAttempt()).isEqualTo(1);
        message.ack();
    }

    @Test
    void asyncPublishesCompleteOnTheSdksNonBlockingClient() throws Exception {
        CompletingThreads threads = new CompletingThreads();
        Channel c = channel("tck-threads-" + UUID.randomUUID().toString().substring(0, 8), null);

        threads.track(() -> c.publisher().async(threads.executor()).publish("one")).get(30, TimeUnit.SECONDS);
        threads.track(() -> c.publisher().async(threads.executor()).publishAll(List.of(OutgoingMessage.of("two"), OutgoingMessage.of("three"))))
                .get(30, TimeUnit.SECONDS);

        threads.assertNonBlocking(2, Thread.currentThread());
    }
}
