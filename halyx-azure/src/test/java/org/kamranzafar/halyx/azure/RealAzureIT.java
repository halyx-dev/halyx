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
package org.kamranzafar.halyx.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.administration.ServiceBusAdministrationClient;
import com.azure.messaging.servicebus.administration.ServiceBusAdministrationClientBuilder;
import com.azure.messaging.servicebus.administration.models.CreateQueueOptions;
import com.azure.messaging.servicebus.administration.models.CreateSubscriptionOptions;
import com.azure.messaging.servicebus.administration.models.CreateTopicOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.kamranzafar.halyx.Halyx;
import org.kamranzafar.halyx.CloudContext;
import org.kamranzafar.halyx.config.ProviderConfig;
import org.kamranzafar.halyx.messaging.ClaimCheck;
import org.kamranzafar.halyx.messaging.OutgoingMessage;
import org.kamranzafar.halyx.messaging.ReceivedMessage;
import org.kamranzafar.halyx.messaging.SubscriptionConfig;
import org.kamranzafar.halyx.messaging.TopicConfig;
import org.kamranzafar.halyx.nosql.DocumentStore;
import org.kamranzafar.halyx.nosql.TableConfig;
import org.kamranzafar.halyx.parameters.ParameterStoreConfig;
import org.kamranzafar.halyx.storage.BlobStore;
import org.kamranzafar.halyx.tck.BlobStoreConformanceTest;
import org.kamranzafar.halyx.tck.DocumentStoreConformanceTest;
import org.kamranzafar.halyx.tck.MessagingConformanceTest;
import org.kamranzafar.halyx.tck.ParameterStoreConformanceTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conformance kits against real Azure (tier 1: Blob Storage, Cosmos DB, Service Bus, Key Vault, App
 * Configuration), with Entra ID only: shared keys and local auth are off on every resource. Runs only with
 * {@code -Dhalyx.real.azure=true}, normally through {@code scripts/azure-real-cloud.sh}, which creates the
 * resources with Terraform and passes their names in. Credentials are {@code DefaultAzureCredential}, normally
 * the test service principal from the environment. Service Bus topics, subscriptions and queues are created
 * here and deleted at the end (and with the resource group).
 */
@EnabledIfSystemProperty(named = "halyx.real.azure", matches = "true")
class RealAzureIT {

    private static final String RUN = System.getProperty("halyx.real.azure.run", "local");
    private static final String STORAGE_ACCOUNT = System.getProperty("halyx.real.azure.storage-account");
    private static final String CONTAINER = System.getProperty("halyx.real.azure.container");
    private static final String SERVICE_BUS = System.getProperty("halyx.real.azure.servicebus-namespace");
    private static final String COSMOS = System.getProperty("halyx.real.azure.cosmos-endpoint");
    private static final String KEY_VAULT = System.getProperty("halyx.real.azure.key-vault-url");
    private static final String APP_CONFIG = System.getProperty("halyx.real.azure.appconfig-endpoint");

    private static Halyx halyx;
    private static ServiceBusAdministrationClient admin;
    private static final List<String> topics = new CopyOnWriteArrayList<>();
    private static final List<String> queues = new CopyOnWriteArrayList<>();
    private static final AtomicInteger counter = new AtomicInteger();

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")
                .property(AzureProperties.STORAGE_ACCOUNT, STORAGE_ACCOUNT)
                .property(AzureProperties.SERVICE_BUS_NAMESPACE, SERVICE_BUS)
                .property(AzureProperties.COSMOS_ENDPOINT, COSMOS)
                .property(AzureProperties.COSMOS_DATABASE, "halyx")).build();
        admin = new ServiceBusAdministrationClientBuilder()
                .credential(SERVICE_BUS, new DefaultAzureCredentialBuilder().build())
                .buildClient();
    }

    @AfterAll
    static void stop() {
        // Everything this run created that Terraform doesn't own (the resource group takes it anyway).
        for (String topic : topics) {
            try {
                admin.deleteTopic(topic);
            } catch (RuntimeException e) {
                System.err.println("Could not delete topic " + topic + ": " + e.getMessage());
            }
        }
        for (String queue : queues) {
            try {
                admin.deleteQueue(queue);
            } catch (RuntimeException e) {
                System.err.println("Could not delete queue " + queue + ": " + e.getMessage());
            }
        }
        if (halyx != null) {
            halyx.close();
        }
    }

    static CloudContext azure() {
        return halyx.provider("azure");
    }

    @Nested
    class Blob extends BlobStoreConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return azure().blobStore(CONTAINER);
        }
    }

    @Nested
    class Cosmos extends DocumentStoreConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
            if (sorted) {
                table.sortKey("sk");
            }
            return azure().documentStore(indexes(table, sorted).build());
        }
    }

    @Nested
    class ServiceBus extends MessagingConformanceTest {
        @Override
        protected Channel channel(String name, ClaimCheck claimCheck) {
            return create(claimCheck, false);
        }

        /** Session-enabled with duplicate detection: ordering keys, deduplication and delays. */
        @Override
        protected Channel orderedChannel(String name) {
            return create(null, true);
        }

        @Override
        protected BlobStore claimCheckStore() {
            return azure().blobStore(CONTAINER);
        }

        private Channel create(ClaimCheck claimCheck, boolean sessions) {
            String topic = "halyx-" + RUN + "-" + counter.incrementAndGet();
            admin.createTopic(topic, new CreateTopicOptions()
                    .setDefaultMessageTimeToLive(Duration.ofHours(1))
                    .setDuplicateDetectionRequired(sessions)
                    .setDuplicateDetectionHistoryTimeWindow(Duration.ofMinutes(1)));
            topics.add(topic);
            admin.createSubscription(topic, "sub", new CreateSubscriptionOptions()
                    .setDefaultMessageTimeToLive(Duration.ofHours(1))
                    .setLockDuration(Duration.ofSeconds(5))
                    .setMaxDeliveryCount(10)
                    .setDeadLetteringOnMessageExpiration(false)
                    .setSessionRequired(sessions));

            TopicConfig.Builder topicConfig = TopicConfig.builder(topic)
                    .property(AzureProperties.SERVICE_BUS_SESSIONS, String.valueOf(sessions));
            SubscriptionConfig.Builder subscriptionConfig = SubscriptionConfig.builder(topic + "/subscriptions/sub")
                    .property(AzureProperties.SERVICE_BUS_SESSIONS, String.valueOf(sessions));
            if (claimCheck != null) {
                topicConfig.claimCheck(claimCheck);
                subscriptionConfig.claimCheck(claimCheck);
            }
            return new Channel(azure().publisher(topicConfig.build()), azure().subscription(subscriptionConfig.build()));
        }

        @Test
        void queuesWorkPointToPoint() {
            String queue = "halyx-" + RUN + "-q" + counter.incrementAndGet();
            admin.createQueue(queue, new CreateQueueOptions().setLockDuration(Duration.ofSeconds(5)).setMaxDeliveryCount(10));
            queues.add(queue);
            var publisher = azure().publisher(TopicConfig.builder(queue)
                    .property(AzureProperties.SERVICE_BUS_ENTITY, "queue").build());
            var subscription = azure().subscription(queue);
            publisher.publish(OutgoingMessage.builder("direct").attribute("via", "queue").build());

            ReceivedMessage message = receiveOne(subscription);
            assertThat(message.bodyAsString()).isEqualTo("direct");
            assertThat(message.attributes()).containsEntry("via", "queue");
            message.ack();
        }
    }

    @Nested
    class KeyVault extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return azure();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets().location(KEY_VAULT);
        }

        /** Key Vault names allow only letters, digits and dashes; "--" is native but not portable. */
        @Override
        protected String nativePrefix(String unique) {
            return unique + "-";
        }

        @Override
        protected String nativeName() {
            return "db--password";
        }

        /** Key Vault deletes and recoveries finish asynchronously, and the driver doesn't wait for them. */
        @Override
        protected Duration deleteLag() {
            return Duration.ofSeconds(60);
        }
    }

    @Nested
    class AppConfiguration extends ParameterStoreConformanceTest {
        @Override
        protected CloudContext context() {
            return azure();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().location(APP_CONFIG);
        }
    }
}
