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

import com.azure.identity.DefaultAzureCredentialBuilder;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.azure.eventhubs.EventHubsEntraTokens;
import dev.halyx.config.ProviderConfig;
import dev.halyx.kafka.KafkaStreamClients;
import dev.halyx.kafka.OAuthBearerLoginHandler;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import dev.halyx.tck.StreamConformanceTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * The stream kit against real Event Hubs namespaces' Kafka endpoints (tier 4), with Microsoft Entra ID login only
 * (shared access keys are off). Runs only with {@code -Dhalyx.real.azure.streams=true}, normally through
 * {@code TIER=4 scripts/azure-real-cloud.sh}.
 *
 * <p>Each test creates its topics (event hubs) through Kafka's admin API, logged in the same way, and older ones
 * are deleted as it goes. A Standard namespace stopped accepting new event hubs after about twenty had been
 * created in a run, deleted or not, so tests move to the next namespace before one has created
 * {@value #CREATIONS_PER_NAMESPACE}; a test never changes namespace part-way. The live topic count is logged
 * before each creation.
 */
@EnabledIfSystemProperty(named = "halyx.real.azure.streams", matches = "true")
class RealAzureStreamIT {

    private static final Logger LOG = LoggerFactory.getLogger(RealAzureStreamIT.class);
    private static final List<String> NAMESPACES = Arrays.asList(
            System.getProperty("halyx.real.azure.streams.namespaces", "").split(","));
    /** Topics created in one namespace before the next test moves on; a test creates at most two. */
    private static final int CREATIONS_PER_NAMESPACE = 12;
    /** Event hubs kept at once in a namespace: a test's topic and its dead-letter topic. */
    private static final int KEEP = 2;

    private static Halyx halyx;
    private static final List<Admin> ADMINS = new ArrayList<>();
    private static final List<Deque<String>> LIVE = new ArrayList<>();
    private static final int[] CREATED = new int[NAMESPACES.size()];
    private static volatile int current;

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("azure", ProviderConfig.of("azure")).build();
        for (String namespace : NAMESPACES) {
            Map<String, Object> login = new HashMap<>(AzureConnection.EVENT_HUBS_DEFAULTS);
            login.put("bootstrap.servers", bootstrap(namespace));
            login.put("security.protocol", "SASL_SSL");
            login.put("sasl.mechanism", "OAUTHBEARER");
            login.put("sasl.login.callback.handler.class", OAuthBearerLoginHandler.class.getName());
            login.put("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required "
                    + "halyxLogin=\"real-azure-admin-" + namespace + "\";");
            login.put(KafkaStreamClients.CREDENTIALS, new EventHubsEntraTokens(
                    new DefaultAzureCredentialBuilder().build(), EventHubsEntraTokens.scope(bootstrap(namespace))));
            ADMINS.add(Admin.create(login));
            LIVE.add(new ArrayDeque<>());
        }
    }

    @AfterAll
    static void stop() {
        try {
            for (int i = 0; i < ADMINS.size(); i++) {
                deleteTopics(i, List.copyOf(LIVE.get(i)));
                ADMINS.get(i).close(Duration.ofSeconds(30));
            }
        } finally {
            if (halyx != null) {
                halyx.close();
            }
        }
    }

    private static String bootstrap(String namespace) {
        return namespace + ":9093";
    }

    private static void deleteTopics(int namespace, List<String> topics) {
        if (topics.isEmpty()) {
            return;
        }
        try {
            ADMINS.get(namespace).deleteTopics(topics).all().get();
            LIVE.get(namespace).removeAll(topics);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not delete event hubs " + topics, e.getCause());
        }
    }

    /**
     * Creates an event hub. A test's first topic may move the test to the next namespace; its dead-letter topic
     * ({@code -dlt}) stays with it.
     */
    static synchronized void createTopic(String topic, int partitions) {
        if (!topic.endsWith("-dlt") && CREATED[current] + 2 > CREATIONS_PER_NAMESPACE) {
            if (current + 1 >= NAMESPACES.size()) {
                throw new IllegalStateException("All " + NAMESPACES.size() + " namespaces have created their topics");
            }
            current++;
        }
        int namespace = current;
        Admin admin = ADMINS.get(namespace);
        Deque<String> live = LIVE.get(namespace);
        while (live.size() >= KEEP) {
            deleteTopics(namespace, List.of(live.peekFirst()));
        }
        try {
            LOG.info("Namespace {}: {} topics listed, {} created so far, before creating {}", NAMESPACES.get(namespace),
                    admin.listTopics().names().get().size(), CREATED[namespace], topic);
            admin.createTopics(List.of(new NewTopic(topic, Optional.of(partitions), Optional.empty()))).all().get();
            CREATED[namespace]++;
            live.addLast(topic);
            awaitServed(admin, topic, partitions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PolicyViolationException) {
                LOG.warn("Namespace {} refused topic {} after {} creations", NAMESPACES.get(namespace), topic,
                        CREATED[namespace]);
            }
            throw new IllegalStateException("Could not create event hub " + topic, e.getCause());
        }
    }

    private static void awaitServed(Admin admin, String topic, int partitions) throws InterruptedException {
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        for (int p = 0; p < partitions; p++) {
            latest.put(new TopicPartition(topic, p), OffsetSpec.latest());
        }
        Instant deadline = Instant.now().plusSeconds(60);
        while (true) {
            try {
                admin.listOffsets(latest).all().get();
                return;
            } catch (ExecutionException notYetServed) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Event hub " + topic + " not served after 60s", notYetServed.getCause());
                }
                Thread.sleep(500);
            }
        }
    }

    @Nested
    class EventHubs extends StreamConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("azure");
        }

        @Override
        protected StreamConfig.Builder cluster() {
            return StreamConfig.builder(bootstrap(NAMESPACES.get(current))).auth(StreamAuth.iam());
        }

        @Override
        protected void createTopic(String topic, int partitions) {
            RealAzureStreamIT.createTopic(topic, partitions);
        }

        @Override
        protected Duration timeout() {
            return Duration.ofSeconds(90);
        }
    }
}
