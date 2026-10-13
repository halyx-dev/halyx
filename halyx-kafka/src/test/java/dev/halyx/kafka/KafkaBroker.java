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
package dev.halyx.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import dev.halyx.stream.StreamAuth;
import dev.halyx.stream.StreamConfig;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/** Apache Kafka in Docker for the integration tests, with topic auto-creation off as on managed clusters. */
final class KafkaBroker {

    static final String IMAGE = System.getProperty("kafka.image", "apache/kafka:3.9.1");

    private KafkaBroker() {
    }

    static KafkaContainer plaintext() {
        return new KafkaContainer(IMAGE).withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
    }

    /**
     * Clients log in with SASL {@code OAUTHBEARER} on the external listener. The broker's default validator accepts
     * unsigned tokens ({@code "alg":"none"}), which stand in for cloud IAM tokens.
     */
    static KafkaContainer oauthBearer() {
        return plaintext()
                .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "BROKER:PLAINTEXT,PLAINTEXT:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
                .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_SASL_ENABLED_MECHANISMS", "OAUTHBEARER")
                .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_OAUTHBEARER_SASL_JAAS_CONFIG",
                        "org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginModule required;")
                // Connections log in again when their token expires, so token refresh is exercised.
                .withEnv("KAFKA_CONNECTIONS_MAX_REAUTH_MS", "10000");
    }

    /** Admin settings for the {@link #oauthBearer()} broker: Halyx' own login with an unsigned token. */
    static Map<String, Object> unsignedLogin() {
        StreamConfig cluster = StreamConfig.builder("unused:9092").auth(StreamAuth.iam())
                .property("kafka.security.protocol", "SASL_PLAINTEXT").build();
        Map<String, Object> login = KafkaStreamClients.clientProperties(cluster,
                () -> OAuthBearerLoginIT.unsignedToken("admin", Duration.ofMinutes(10)), Map.of());
        login.remove("bootstrap.servers");
        return login;
    }

    static void createTopic(KafkaContainer broker, String topic, int partitions) {
        createTopic(broker, topic, partitions, Map.of());
    }

    /**
     * Creates a topic and waits until the broker serves every partition. Sending to a partition before then fails
     * with NOT_LEADER_OR_FOLLOWER, after which an idempotent producer's pipelined batches can loop on
     * OUT_OF_ORDER_SEQUENCE_NUMBER until they expire; on a managed cluster topics exist long before they're used.
     */
    static void createTopic(KafkaContainer broker, String topic, int partitions, Map<String, Object> login) {
        Map<String, Object> settings = new HashMap<>(login);
        settings.put("bootstrap.servers", broker.getBootstrapServers());
        try (Admin admin = Admin.create(settings)) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
            awaitServed(admin, topic, partitions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not create topic " + topic, e.getCause());
        }
    }

    /** Waits until every partition answers a ListOffsets request, which only its serving leader does. */
    static void awaitServed(Admin admin, String topic, int partitions) throws InterruptedException {
        Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
        for (int p = 0; p < partitions; p++) {
            latest.put(new TopicPartition(topic, p), OffsetSpec.latest());
        }
        Instant deadline = Instant.now().plusSeconds(30);
        while (true) {
            try {
                admin.listOffsets(latest).all().get();
                return;
            } catch (ExecutionException notYetServed) {
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException("Partitions of " + topic + " not served after 30s", notYetServed.getCause());
                }
                Thread.sleep(100);
            }
        }
    }
}
