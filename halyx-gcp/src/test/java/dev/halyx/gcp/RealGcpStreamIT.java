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

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import dev.halyx.CloudContext;
import dev.halyx.Halyx;
import dev.halyx.config.ProviderConfig;
import dev.halyx.gcp.kafka.ManagedKafkaTokens;
import dev.halyx.kafka.KafkaStreamClients;
import dev.halyx.kafka.OAuthBearerLoginHandler;
import dev.halyx.storage.BlobStore;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import dev.halyx.tck.StreamConformanceTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * The stream kit against a real Google Cloud Managed Service for Apache Kafka cluster (tier 4), with Google IAM
 * login. Runs only with {@code -Dhalyx.real.gcp.streams=true}, normally through
 * {@code TIER=4 cloud-testing/scripts/gcp-real-cloud.sh}, which runs it on a VM inside the cluster's VPC: the cluster has no
 * public endpoint. Credentials are Application Default Credentials: on the runner, the VM's service account.
 *
 * <p>Each test creates its topics through Kafka's admin API, logged in with the same IAM tokens, and older ones
 * are deleted as it goes; the cluster is destroyed after the run.
 */
@EnabledIfSystemProperty(named = "halyx.real.gcp.streams", matches = "true")
class RealGcpStreamIT {

    private static final String PROJECT = System.getProperty("halyx.real.gcp.project");
    private static final String BOOTSTRAP = System.getProperty("halyx.real.gcp.streams.bootstrap");
    private static final String CLAIMS_BUCKET = System.getProperty("halyx.real.gcp.streams.claims-bucket");
    /** Topics kept at once: a test's topic and its dead-letter topic. */
    private static final int KEEP = 2;

    private static Halyx halyx;
    private static Admin admin;
    private static final Deque<String> LIVE = new ArrayDeque<>();

    @BeforeAll
    static void start() {
        halyx = Halyx.builder().register("gcp", ProviderConfig.of("gcp").property(GcpProperties.PROJECT_ID, PROJECT)).build();
        Map<String, Object> login = new HashMap<>();
        login.put("bootstrap.servers", BOOTSTRAP);
        login.put("security.protocol", "SASL_SSL");
        login.put("sasl.mechanism", "OAUTHBEARER");
        login.put("sasl.login.callback.handler.class", OAuthBearerLoginHandler.class.getName());
        login.put("sasl.jaas.config", "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required "
                + "halyxLogin=\"real-gcp-admin\";");
        login.put(KafkaStreamClients.CREDENTIALS, ManagedKafkaTokens.applicationDefault(Optional.empty()));
        admin = Admin.create(login);
    }

    @AfterAll
    static void stop() {
        try {
            if (admin != null) {
                deleteTopics(List.copyOf(LIVE));
                admin.close(Duration.ofSeconds(30));
            }
        } finally {
            if (halyx != null) {
                halyx.close();
            }
        }
    }

    private static void deleteTopics(List<String> topics) {
        if (topics.isEmpty()) {
            return;
        }
        try {
            admin.deleteTopics(topics).all().get();
            LIVE.removeAll(topics);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not delete topics " + topics, e.getCause());
        }
    }

    static synchronized void createTopic(String topic, int partitions) {
        while (LIVE.size() >= KEEP) {
            deleteTopics(List.of(LIVE.peekFirst()));
        }
        try {
            admin.createTopics(List.of(new NewTopic(topic, Optional.of(partitions), Optional.empty()))).all().get();
            LIVE.addLast(topic);
            awaitServed(topic, partitions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not create topic " + topic, e.getCause());
        }
    }

    /** Waits until the cluster serves every partition: only then does ListOffsets answer. */
    private static void awaitServed(String topic, int partitions) throws InterruptedException {
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
                    throw new IllegalStateException("Topic " + topic + " not served after 60s", notYetServed.getCause());
                }
                Thread.sleep(500);
            }
        }
    }

    @Nested
    class ManagedKafka extends StreamConformanceTest {
        @Override
        protected CloudContext context() {
            return halyx.provider("gcp");
        }

        @Override
        protected StreamConfig.Builder cluster() {
            return StreamConfig.builder(BOOTSTRAP).auth(StreamAuth.iam());
        }

        @Override
        protected void createTopic(String topic, int partitions) {
            RealGcpStreamIT.createTopic(topic, partitions);
        }

        @Override
        protected BlobStore claimCheckStore() {
            return halyx.provider("gcp").blobStore(CLAIMS_BUCKET);
        }

        @Override
        protected Duration timeout() {
            return Duration.ofSeconds(90);
        }
    }
}
