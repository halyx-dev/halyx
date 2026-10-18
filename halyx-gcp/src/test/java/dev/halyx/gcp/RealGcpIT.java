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

import com.google.cloud.pubsub.v1.SubscriptionAdminClient;
import com.google.cloud.pubsub.v1.TopicAdminClient;
import com.google.pubsub.v1.Subscription;
import com.google.pubsub.v1.Topic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import dev.halyx.Halyx;
import dev.halyx.CloudContext;
import dev.halyx.config.ProviderConfig;
import dev.halyx.events.EventPublisher;
import dev.halyx.messaging.ClaimCheck;
import dev.halyx.messaging.SubscriptionConfig;
import dev.halyx.messaging.TopicConfig;
import dev.halyx.nosql.DocumentStore;
import dev.halyx.nosql.TableConfig;
import dev.halyx.parameters.ParameterStoreConfig;
import dev.halyx.storage.BlobStore;
import dev.halyx.tck.BlobStoreConformanceTest;
import dev.halyx.tck.DocumentStoreConformanceTest;
import dev.halyx.tck.ReactiveDocumentRepositoryConformanceTest;
import dev.halyx.reactor.ReactiveHalyx;
import dev.halyx.tck.EventBusConformanceTest;
import dev.halyx.tck.MessagingConformanceTest;
import dev.halyx.tck.ParameterStoreConformanceTest;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import dev.halyx.tck.DocumentRepositoryConformanceTest;

/**
 * The conformance kits against real GCP (tier 1: Cloud Storage, Firestore, Pub/Sub, Secret Manager,
 * Parameter Manager). Runs only with {@code -Dhalyx.real.gcp=true}, normally through
 * {@code scripts/gcp-real-cloud.sh}, which creates the bucket and a Firestore database of the run's own with
 * Terraform and passes their names in. Credentials are Application Default Credentials (normally
 * impersonating the test service account). Topics and subscriptions are created here, named and labelled
 * after the run, and deleted at the end; secrets and parameters use the kit's random tck- names.
 */
@EnabledIfSystemProperty(named = "halyx.real.gcp", matches = "true")
class RealGcpIT {

    private static final String PROJECT = System.getProperty("halyx.real.gcp.project");
    private static final String REGION = System.getProperty("halyx.real.gcp.region", "australia-southeast1");
    private static final String RUN = System.getProperty("halyx.real.gcp.run", "local");
    private static final String BUCKET = System.getProperty("halyx.real.gcp.bucket");
    private static final String DATABASE = System.getProperty("halyx.real.gcp.firestore-database");
    private static final String MESSAGE_BUS = System.getProperty("halyx.real.gcp.message-bus");
    private static final String EVENTS_INBOX = System.getProperty("halyx.real.gcp.events-inbox");

    private static Halyx halyx;
    private static TopicAdminClient topics;
    private static SubscriptionAdminClient subscriptions;
    private static final List<String> createdTopics = new CopyOnWriteArrayList<>();
    private static final List<String> createdSubscriptions = new CopyOnWriteArrayList<>();
    private static final AtomicInteger counter = new AtomicInteger();

    @BeforeAll
    static void start() throws IOException {
        halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp")
                .property(GcpProperties.PROJECT_ID, PROJECT)
                .property(GcpProperties.FIRESTORE_DATABASE, DATABASE)).build();
        topics = TopicAdminClient.create();
        subscriptions = SubscriptionAdminClient.create();
    }

    private static ReactiveHalyx reactiveHalyx;

    /** Reactive views on virtual threads, for the reactive repository kit; closed after the class. */
    static synchronized ReactiveHalyx reactiveHalyx() {
        if (reactiveHalyx == null) {
            reactiveHalyx = ReactiveHalyx.withVirtualThreads(halyx);
        }
        return reactiveHalyx;
    }

    @AfterAll
    static void stop() {
        if (reactiveHalyx != null) {
            reactiveHalyx.close();
        }
        // Everything this run created that Terraform doesn't own.
        for (String subscription : createdSubscriptions) {
            try {
                subscriptions.deleteSubscription(subscription);
            } catch (RuntimeException e) {
                System.err.println("Could not delete subscription " + subscription + ": " + e.getMessage());
            }
        }
        for (String topic : createdTopics) {
            try {
                topics.deleteTopic(topic);
            } catch (RuntimeException e) {
                System.err.println("Could not delete topic " + topic + ": " + e.getMessage());
            }
        }
        if (subscriptions != null) {
            subscriptions.close();
        }
        if (topics != null) {
            topics.close();
        }
        if (halyx != null) {
            halyx.close();
        }
    }

    static CloudContext gcp() {
        return halyx.provider("gcp");
    }

    @Nested
    class CloudStorage extends BlobStoreConformanceTest {
        @Override
        protected BlobStore blobStore() {
            return gcp().blobStore(BUCKET);
        }
    }

    @Nested
    class Firestore extends DocumentStoreConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
            if (sorted) {
                table.sortKey("sk");
            }
            return gcp().documentStore(indexes(table, sorted).build());
        }
    }

    @Nested
    class ReactiveFirestoreRepositories extends ReactiveDocumentRepositoryConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
            if (sorted) {
                table.sortKey("sk");
            }
            return gcp().documentStore(DocumentStoreConformanceTest.indexes(table, sorted).build());
        }

        @Override
        protected ReactiveHalyx reactive() {
            return reactiveHalyx();
        }
    }

    @Nested
    class FirestoreRepositories extends DocumentRepositoryConformanceTest {
        @Override
        protected DocumentStore store(boolean sorted) {
            TableConfig.Builder table = TableConfig.builder(sorted ? "items-sorted" : "items");
            if (sorted) {
                table.sortKey("sk");
            }
            return gcp().documentStore(DocumentStoreConformanceTest.indexes(table, sorted).build());
        }
    }

    @Nested
    class PubSub extends MessagingConformanceTest {
        @Override
        protected Channel channel(String name, ClaimCheck claimCheck) {
            return create(claimCheck, false);
        }

        @Override
        protected Channel orderedChannel(String name) {
            return create(null, true);
        }

        @Override
        protected BlobStore claimCheckStore() {
            return gcp().blobStore(BUCKET);
        }

        private Channel create(ClaimCheck claimCheck, boolean ordered) {
            String name = "halyx-" + RUN + "-" + counter.incrementAndGet();
            String topic = "projects/" + PROJECT + "/topics/" + name;
            String subscription = "projects/" + PROJECT + "/subscriptions/" + name + "-sub";
            Map<String, String> labels = Map.of("halyx-test", RUN);
            topics.createTopic(Topic.newBuilder().setName(topic).putAllLabels(labels).build());
            createdTopics.add(topic);
            subscriptions.createSubscription(Subscription.newBuilder().setName(subscription).setTopic(topic)
                    .setAckDeadlineSeconds(10).setEnableMessageOrdering(ordered).putAllLabels(labels).build());
            createdSubscriptions.add(subscription);

            TopicConfig.Builder topicConfig = TopicConfig.builder(name);
            SubscriptionConfig.Builder subscriptionConfig = SubscriptionConfig.builder(name + "-sub");
            if (claimCheck != null) {
                topicConfig.claimCheck(claimCheck);
                subscriptionConfig.claimCheck(claimCheck);
            }
            return new Channel(gcp().publisher(topicConfig.build()), gcp().subscription(subscriptionConfig.build()));
        }
    }

    @Nested
    class Eventarc extends EventBusConformanceTest {
        private dev.halyx.messaging.Subscription inbox;

        @Override
        protected EventPublisher publisher() {
            return gcp().eventPublisher(MESSAGE_BUS);
        }

        @Override
        protected dev.halyx.messaging.Subscription inbox() {
            if (inbox == null) {
                inbox = gcp().subscription(EVENTS_INBOX);
            }
            return inbox;
        }

        @Override
        protected Map<String, String> knownEnvironmentGaps() {
            return Map.of("eventsWithoutDataArrive", "an Eventarc pipeline's publish of an event without data is "
                    + "refused by Pub/Sub (HTTP 400), so such events never reach the topic");
        }

        /** A new pipeline can take a minute or two to start delivering. */
        @Override
        protected Duration timeout() {
            return Duration.ofSeconds(120);
        }
    }

    @Nested
    class SecretManager extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        /** Secret Manager's latest alias is eventually consistent; only reads by version number are strong. */
        @Override
        protected Duration overwriteLag() {
            return Duration.ofSeconds(30);
        }

        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.secrets();
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }

    @Nested
    class ParameterManagerGlobal extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters();
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }

    @Nested
    class ParameterManagerRegional extends ParameterStoreConformanceTest {
        @Override
        protected boolean nonBlockingAsync() {
            return true;
        }

        @Override
        protected CloudContext context() {
            return gcp();
        }

        @Override
        protected ParameterStoreConfig.Builder config() {
            return ParameterStoreConfig.parameters().location(REGION);
        }

        @Override
        protected String nativePrefix(String unique) {
            return unique + "_";
        }
    }
}
